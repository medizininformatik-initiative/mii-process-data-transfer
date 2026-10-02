package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.variables.DataResource;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.service.TaskHelper;
import dev.dsf.bpe.v2.variables.Variables;

public class ReadData implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ReadData.class);

	private static final String ISO_8601_DURATION_STRING = "^P(?:([0-9]+)Y)?(?:([0-9]+)M)?(?:([0-9]+)D)?(T(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?$";
	private static final Pattern ISO_8601_DURATION = Pattern.compile(ISO_8601_DURATION_STRING);

	private final String fhirStoreId;
	private final boolean fhirBinaryStreamReadEnabled;
	private final String statusTimerInterval;

	public ReadData(String fhirStoreId, boolean fhirBinaryStreamReadEnabled, String statusTimerInterval)
	{
		this.fhirStoreId = fhirStoreId;
		this.fhirBinaryStreamReadEnabled = fhirBinaryStreamReadEnabled;
		this.statusTimerInterval = statusTimerInterval;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		if (!ISO_8601_DURATION.matcher(statusTimerInterval).matches())
			throw new IllegalArgumentException(
					"statusTimerInterval '" + statusTimerInterval + "' not in ISO 8601 time duration format");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_STATUS_TIMER_INTERVAL, statusTimerInterval);

		Task task = variables.getStartTask();
		String dmsIdentifier = getDmsIdentifier(api.getTaskHelper(), task);
		String consortiumIdentifier = getConsortiumIdentifier(api.getTaskHelper(), task);
		String projectIdentifier = getProjectIdentifier(api.getTaskHelper(), task);

		DsfClient client = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId);

		logger.info(
				"Executing data-set transfer for DMS '{}|{}' and project-identifier '{}' with status timer interval '{}' in Task '{}'",
				consortiumIdentifier, dmsIdentifier, projectIdentifier, statusTimerInterval,
				api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

		DocumentReference documentReference = readDocumentReference(api, client, task, dmsIdentifier,
				projectIdentifier);

		Stream<DataResource> attachments = readAttachments(client, documentReference);
		List<Resource> resources = getResources(attachments);

		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER, dmsIdentifier);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER, consortiumIdentifier);
		variables.setFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE,
				documentReference);
		variables.setFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DATA_RESOURCES, resources);
	}

	private String getProjectIdentifier(TaskHelper helper, Task task)
	{
		List<String> identifiers = helper
				.getInputParameterValues(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER, Identifier.class)
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).toList();

		if (identifiers.isEmpty())
			throw new IllegalArgumentException("Task.input:project-identifier missing");

		if (identifiers.size() > 1)
			logger.warn("Found {} Task.input:project-identifier, using the first '{}' from Task '{}'",
					identifiers.size(), identifiers.getFirst(), helper.getLocalVersionlessAbsoluteUrl(task));

		return identifiers.getFirst();
	}

	private String getConsortiumIdentifier(TaskHelper helper, Task task)
	{
		return helper
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_CONSORTIUM_IDENTIFIER, Reference.class)
				.orElse(new Reference().setIdentifier(NamingSystems.OrganizationIdentifier.withValue(
						ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM)))
				.getIdentifier().getValue();
	}

	private String getDmsIdentifier(TaskHelper helper, Task task)
	{
		return helper
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER, Reference.class)
				.orElseThrow(() -> new IllegalArgumentException("Task.input:dms-identifier missing")).getIdentifier()
				.getValue();
	}

	private DsfClient getDsfClientForFhirStore(DsfClientProvider provider, String fhirStoreId)
	{
		return provider.getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("DSF client config '" + fhirStoreId + "' not configured"));
	}

	private DocumentReference readDocumentReference(ProcessPluginApi api, DsfClient client, Task task,
			String dmsIdentifier, String projectIdentifier)
	{
		String projectIdentifierWithSystem = ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER + "|"
				+ projectIdentifier;
		Map<String, List<String>> identifierSearchParam = Map.of("identifier", List.of(projectIdentifierWithSystem));

		List<DocumentReference> documentReferences = client.search(DocumentReference.class, identifierSearchParam)
				.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

		if (documentReferences.isEmpty())
			throw new RuntimeException("Could not find DocumentReference with project-identifier '" + projectIdentifier
					+ "' for DMS  '" + dmsIdentifier + "'");

		DocumentReference documentReference = documentReferences.getFirst();

		if (documentReferences.size() > 1)
			logger.warn("Found {} DocumentReferences, using the first '{}' for Task '{}'", documentReferences.size(),
					documentReference.getIdElement().getValue(),
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

		api.getDataLogger()
				.log("DocumentReference with project-identifier '" + projectIdentifier + "' for DMS  '" + dmsIdentifier
						+ "' and Task '" + api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "'",
						documentReference);
		return documentReference;
	}

	private Stream<DataResource> readAttachments(DsfClient client, DocumentReference documentReference)
	{
		return Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(client, a));
	}

	private DataResource readAttachment(DsfClient client, Attachment attachment)
	{
		String url = getAttachmentUrl(attachment);
		IdType urlIdType = checkValidKdsFhirStoreUrlAndGetIdType(client, url);

		if (ResourceType.Binary.name().equals(urlIdType.getResourceType()) && fhirBinaryStreamReadEnabled)
		{
			String mimetype = getAttachmentMimeType(attachment);
			return DataResource.of(urlIdType, mimetype);
		}
		else
		{
			Resource resource = client.read(urlIdType.getResourceType(), urlIdType.getIdPart());
			return DataResource.of(resource);
		}
	}

	private String getAttachmentUrl(Attachment attachment)
	{
		return Optional.of(attachment).filter(Attachment::hasUrl).map(Attachment::getUrl)
				.orElseThrow(() -> new IllegalArgumentException("DocumentReference.content.attachment.url missing"));
	}

	private String getAttachmentMimeType(Attachment attachment)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType).orElseThrow(
				() -> new IllegalArgumentException("DocumentReference.content.attachment.contentType missing"));
	}

	private IdType checkValidKdsFhirStoreUrlAndGetIdType(DsfClient client, String url)
	{
		IdType idType = new IdType(url);

		// expecting no baseUrl or, baseUrl equal to client baseUrl
		boolean hasValidBaseUrl = !idType.hasBaseUrl() || client.getBaseUrl().equals(idType.getBaseUrl());
		boolean isResourceReference = idType.hasResourceType() && idType.hasIdPart();

		if (hasValidBaseUrl && isResourceReference)
			return idType;
		else
			throw new RuntimeException("DocumentReference.content.attachment.url '" + url
					+ "' is not valid (baseUrl must match client baseUrl, resource type must be set, id must be set) ");
	}

	private List<Resource> getResources(Stream<DataResource> dataResources)
	{
		List<Resource> resources = dataResources.map(DataResource::toResource).filter(Objects::nonNull).toList();
		return combineListResources(resources);
	}

	private List<Resource> combineListResources(List<Resource> resources)
	{
		ListResource listResource = new ListResource()
				.setEntry(resources.stream().filter(r -> r instanceof ListResource).map(l -> ((ListResource) l))
						.flatMap(l -> l.getEntry().stream()).toList());

		Stream<Resource> notListResources = resources.stream().filter(r -> !(r instanceof ListResource));

		if (!listResource.getEntry().isEmpty())
			return Stream.concat(notListResources, Stream.of(listResource)).toList();
		else
			return notListResources.toList();
	}
}
