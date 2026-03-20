package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.variables.DataResource;
import de.medizininformatik_initiative.process.data_transfer.variables.ProcessConfig;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.service.DataLogger;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.service.TaskHelper;
import dev.dsf.bpe.v2.variables.Variables;

public class ReadData implements ServiceTask
{
	private static final Logger logger = LoggerFactory.getLogger(ReadData.class);

	private final String fhirStoreId;
	private final boolean fhirBinaryStreamReadEnabled;

	public ReadData(String fhirStoreId, boolean fhirBinaryStreamReadEnabled)
	{
		this.fhirStoreId = fhirStoreId;
		this.fhirBinaryStreamReadEnabled = fhirBinaryStreamReadEnabled;
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws ErrorBoundaryEvent, Exception
	{
		Task task = variables.getStartTask();
		String dmsIdentifier = getDmsIdentifier(api.getTaskHelper(), task);
		String consortiumIdentifier = getConsortiumIdentifier(api.getTaskHelper(), task);
		String projectIdentifier = getProjectIdentifier(task);

		DsfClient client = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId);

		ProcessConfig processConfig = new ProcessConfig(Map.of("fhirStoreBaseUrl", client.getBaseUrl(),
				"projectIdentifier", ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER + "|" + projectIdentifier,
				"recipientDms", dmsIdentifier));

		logger.info("Reading data-set {}", processConfig);

		try
		{
			DocumentReference documentReference = readDocumentReference(client, projectIdentifier, api.getDataLogger(),
					processConfig.toString());
			processConfig.add("documentReference.id", documentReference.getId());

			Stream<DataResource> attachments = readAttachments(client, documentReference, processConfig.toString());
			List<Resource> resources = getResources(attachments, api.getDataLogger(), processConfig.toString());

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER, dmsIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER,
					consortiumIdentifier);
			variables.setFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE,
					documentReference);
			variables.setFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DATA_RESOURCES,
					resources);
		}
		catch (Exception exception)
		{
			logger.warn("Reading data-set failed - {} {}", exception.getMessage(), processConfig);
			throw new RuntimeException("Reading data-set failed - " + exception.getMessage(), exception);
		}
	}

	private String getProjectIdentifier(Task task)
	{
		List<String> identifiers = task.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER.equals(c.getCode())))
				.filter(i -> i.getValue() instanceof Identifier).map(i -> (Identifier) i.getValue())
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).toList();

		if (identifiers.isEmpty())
			throw new IllegalArgumentException("Task.input:project-identifier missing");

		if (identifiers.size() > 1)
			logger.warn("Found {} Task.input:project-identifier, using the first '{}'", identifiers.size(),
					identifiers.getFirst());

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
				.orElseThrow(() -> new RuntimeException("DSF client config with id '" + fhirStoreId + "' not found"));
	}

	private DocumentReference readDocumentReference(DsfClient client, String projectIdentifier, DataLogger dataLogger,
			String processConfig)
	{
		String projectIdentifierWithSystem = ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER + "|"
				+ projectIdentifier;
		Map<String, List<String>> identifierSearchParam = Map.of("identifier", List.of(projectIdentifierWithSystem));

		List<DocumentReference> documentReferences = client.search(DocumentReference.class, identifierSearchParam)
				.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

		if (documentReferences.isEmpty())
			throw new RuntimeException("Could not find DocumentReference " + processConfig);

		DocumentReference documentReference = documentReferences.getFirst();

		if (documentReferences.size() > 1)
			logger.warn("Found {} DocumentReferences, using the first with id '{}' {}", documentReferences.size(),
					documentReference.getIdElement().getValue(), processConfig);

		dataLogger.log("DocumentReference " + processConfig, documentReference);
		return documentReference;
	}

	private Stream<DataResource> readAttachments(DsfClient client, DocumentReference documentReference,
			String processConfig)
	{
		return Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(client, a, processConfig));
	}

	private DataResource readAttachment(DsfClient client, Attachment attachment, String processConfig)
	{
		String url = getAttachmentUrl(attachment, processConfig);
		IdType urlIdType = checkValidKdsFhirStoreUrlAndGetIdType(client, url, processConfig);

		if (ResourceType.Binary.name().equals(urlIdType.getResourceType()) && fhirBinaryStreamReadEnabled)
		{
			String mimetype = getAttachmentMimeType(attachment, processConfig);
			return DataResource.of(urlIdType, mimetype);
		}
		else
		{
			Resource resource = client.read(urlIdType.getResourceType(), urlIdType.getIdPart());
			return DataResource.of(resource);
		}
	}

	private String getAttachmentUrl(Attachment attachment, String processConfig)
	{
		return Optional.of(attachment).filter(Attachment::hasUrl).map(Attachment::getUrl).orElseThrow(
				() -> new IllegalArgumentException("DocumentReference.content.attachment.url missing" + processConfig));
	}

	private String getAttachmentMimeType(Attachment attachment, String processConfig)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType)
				.orElseThrow(() -> new IllegalArgumentException(
						"DocumentReference.content.attachment.contentType missing " + processConfig));
	}

	private IdType checkValidKdsFhirStoreUrlAndGetIdType(DsfClient client, String url, String processConfig)
	{
		IdType idType = new IdType(url);

		// expecting no baseUrl or, baseUrl equal to client baseUrl
		boolean hasValidBaseUrl = !idType.hasBaseUrl() || client.getBaseUrl().equals(idType.getBaseUrl());
		boolean isResourceReference = idType.hasResourceType() && idType.hasIdPart();

		if (hasValidBaseUrl && isResourceReference)
			return idType;
		else
			throw new RuntimeException("DocumentReference.content.attachment.url '" + url
					+ "' is not valid (baseUrl must match client baseUrl, resource type must be set, id must be set) "
					+ processConfig);
	}

	private List<Resource> getResources(Stream<DataResource> dataResources, DataLogger dataLogger, String processConfig)
	{
		List<Resource> resources = dataResources.map(DataResource::toResource).filter(Objects::nonNull).toList();
		return combineListResources(resources)
				.peek(r -> dataLogger.log("DocumentReference.content.attachment " + processConfig, r)).toList();
	}

	private Stream<Resource> combineListResources(List<Resource> resources)
	{
		ListResource listResource = new ListResource()
				.setEntry(resources.stream().filter(r -> r instanceof ListResource).map(l -> ((ListResource) l))
						.flatMap(l -> l.getEntry().stream()).toList());

		Stream<Resource> notListResources = resources.stream().filter(r -> !(r instanceof ListResource));

		if (!listResource.getEntry().isEmpty())
			return Stream.concat(notListResources, Stream.of(listResource));
		else
			return notListResources;
	}
}
