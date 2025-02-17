package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.variables.DataResource;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ReadData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ReadData.class);

	private final FhirClientFactory fhirClientFactory;
	private final boolean fhirBinaryStreamReadEnabled;

	private final DataLogger dataLogger;

	public ReadData(ProcessPluginApi api, FhirClientFactory fhirClientFactory, boolean fhirBinaryStreamReadEnabled,
			DataLogger dataLogger)
	{
		super(api);
		this.fhirClientFactory = fhirClientFactory;
		this.fhirBinaryStreamReadEnabled = fhirBinaryStreamReadEnabled;
		this.dataLogger = dataLogger;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
		Objects.requireNonNull(dataLogger, "dataLogger");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String dmsIdentifier = getDmsIdentifier(task);
		String projectIdentifier = getProjectIdentifier(task, dmsIdentifier);

		logger.info(
				"Reading data-set on FHIR store with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				fhirClientFactory.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			DocumentReference documentReference = readDocumentReference(dmsIdentifier, projectIdentifier, task.getId());
			Stream<DataResource> attachments = readAttachments(documentReference, projectIdentifier);
			List<Resource> resources = getResources(attachments, dmsIdentifier, projectIdentifier, task.getId());

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER, dmsIdentifier);
			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE,
					documentReference);
			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, resources);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not read data-set on FHIR store with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					fhirClientFactory.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId(),
					exception.getMessage());

			String error = "Reading data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private String getProjectIdentifier(Task task, String dmsIdentifier)
	{
		List<String> identifiers = task.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER.equals(c.getCode())))
				.filter(i -> i.getValue() instanceof Identifier).map(i -> (Identifier) i.getValue())
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).toList();

		if (identifiers.size() < 1)
			throw new IllegalArgumentException("No project-identifier present in Task.input");

		if (identifiers.size() > 1)
			logger.warn(
					"Found {} project-identifier inputs for DMS '{}' referenced in Task with id '{}', using the first ('{}')",
					identifiers.size(), dmsIdentifier, task.getId(), identifiers.get(0));

		return identifiers.get(0);
	}

	private String getDmsIdentifier(Task task)
	{
		return api.getTaskHelper()
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER, Reference.class)
				.orElseThrow(
						() -> new IllegalArgumentException("No coordinating site identifier present in Task.input"))
				.getIdentifier().getValue();
	}

	private DocumentReference readDocumentReference(String dmsIdentifier, String projectIdentifier, String taskId)
	{
		List<DocumentReference> documentReferences = fhirClientFactory.getStandardFhirClient()
				.searchDocumentReferences(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier)
				.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException("Could not find any DocumentReference with matching project-identifier");

		if (documentReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReferences for DMS '{}' and project-identifier '{}' on FHIR store with baseURL '{}' referenced in Task with id '{}', using the first ('{}')",
					documentReferences.size(), dmsIdentifier, projectIdentifier, fhirClientFactory.getFhirBaseUrl(),
					taskId, documentReferences.get(0).getIdElement().getValue());

		DocumentReference documentReference = documentReferences.get(0);
		dataLogger.logResource("DocumentReference for DMS '" + dmsIdentifier + "' and project-identifier '"
				+ projectIdentifier + "' on FHIR store with baseURL '" + fhirClientFactory.getFhirBaseUrl()
				+ "' referenced in Task with id '" + taskId + "'", documentReference);

		return documentReference;
	}

	private Stream<DataResource> readAttachments(DocumentReference documentReference, String projectIdentifier)
	{
		return Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(a, projectIdentifier));
	}

	private DataResource readAttachment(Attachment attachment, String projectIdentifier)
	{
		String url = getAttachmentUrl(attachment);
		IdType urlIdType = checkValidKdsFhirStoreUrlAndGetIdType(url);

		if (ResourceType.Binary.name().equals(urlIdType.getResourceType()) && fhirBinaryStreamReadEnabled)
		{
			String mimetype = getAttachmentMimeType(attachment, projectIdentifier);
			return DataResource.of(urlIdType, mimetype);
		}
		else
		{
			Resource resource = fhirClientFactory.getStandardFhirClient().read(urlIdType);
			return DataResource.of(resource);
		}
	}

	private String getAttachmentUrl(Attachment attachment)
	{
		return Optional.of(attachment).filter(Attachment::hasUrl).map(Attachment::getUrl).orElseThrow(
				() -> new IllegalArgumentException("Could not find any attachment URLs in DocumentReference"));
	}

	private String getAttachmentMimeType(Attachment attachment, String projectIdentifier)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType)
				.orElseThrow(() -> new IllegalArgumentException(
						"Could not find any attachment contentType (mimeType) in DocumentReference for project-identifier '"
								+ projectIdentifier + "'"));
	}

	private IdType checkValidKdsFhirStoreUrlAndGetIdType(String url)
	{
		IdType idType = new IdType(url);

		// expecting no Base URL or, Base URL equal to KDS client Base URL
		boolean hasValidBaseUrl = !idType.hasBaseUrl()
				|| fhirClientFactory.getFhirBaseUrl().equals(idType.getBaseUrl());
		boolean isResourceReference = idType.hasResourceType() && idType.hasIdPart();

		if (hasValidBaseUrl && isResourceReference)
			return idType;
		else
			throw new IllegalArgumentException("Attachment URL '" + url
					+ "' in DocumentReference is not a valid KDS FHIR store reference (baseUrl must match '"
					+ fhirClientFactory.getFhirBaseUrl() + "', resource type must be set, id must be set)");
	}

	private List<Resource> getResources(Stream<DataResource> dataResources, String dmsIdentifier,
			String projectIdentifier, String taskId)
	{
		return dataResources.map(this::getResource).filter(Objects::nonNull)
				.peek(r -> dataLogger.logResource("Read attachment for DMS '" + dmsIdentifier
						+ "' and project-identifier '" + projectIdentifier + "' on FHIR store with baseURL '"
						+ fhirClientFactory.getFhirBaseUrl() + "' referenced in Task with id '" + taskId + "'", r))
				.toList();
	}

	private Resource getResource(DataResource attachment)
	{
		if (attachment.hasStreamLocation() && fhirBinaryStreamReadEnabled)
		{
			ListResource.ListEntryComponent entry = new ListResource.ListEntryComponent();

			entry.getItem().setReferenceElement(attachment.streamLocation());
			entry.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)
					.setValue(new StringType(attachment.mimetype()));

			return new ListResource().addEntry(entry);
		}
		else if (attachment.hasResource())
			return attachment.resource();
		else
			throw new RuntimeException("Data not available as resource or stream");
	}
}
