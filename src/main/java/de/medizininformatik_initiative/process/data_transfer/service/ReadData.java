package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ReadData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ReadData.class);

	private record DataResource(Resource resource, InputStream stream, String mimetype)
	{
		public static DataResource of(Resource resource)
		{
			return new DataResource(resource, null, null);
		}

		public static DataResource of(InputStream stream, String mimeType)
		{
			return new DataResource(null, stream, mimeType);
		}

		public boolean hasResource()
		{
			return resource != null;
		}

		public boolean hasInputStream()
		{
			return stream != null;
		}
	}

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
		String projectIdentifier = getProjectIdentifier(task);
		String dmsIdentifier = getDmsIdentifier(task);

		logger.info(
				"Reading data-set on FHIR server with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				fhirClientFactory.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			DocumentReference documentReference = readDocumentReference(projectIdentifier, task.getId());
			Stream<DataResource> attachments = readAttachments(documentReference, task.getId());
			List<Resource> resources = getResources(attachments, projectIdentifier);

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER, dmsIdentifier);
			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE,
					documentReference);
			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, resources);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not read data-set on FHIR server with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					fhirClientFactory.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId(),
					exception.getMessage());

			String error = "Read data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
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

		if (identifiers.size() < 1)
			throw new IllegalArgumentException("No project-identifier present in Task with id '" + task.getId() + "'");

		if (identifiers.size() > 1)
			logger.warn("Found {} project-identifiers in Task with id '{}', using only the first", identifiers.size(),
					task.getId());

		return identifiers.get(0);
	}

	private String getDmsIdentifier(Task task)
	{
		return api.getTaskHelper()
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER, Reference.class)
				.orElseThrow(
						() -> new IllegalArgumentException("No coordinating site identifier present in Task with id '"
								+ task.getId() + "', this should have been caught by resource validation"))
				.getIdentifier().getValue();
	}

	private DocumentReference readDocumentReference(String projectIdentifier, String taskId)
	{
		List<DocumentReference> documentReferences = fhirClientFactory.getStandardFhirClient()
				.searchDocumentReferences(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier)
				.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException("Could not find any DocumentReference for project-identifier '"
					+ projectIdentifier + "' on FHIR store with baseUrl '" + fhirClientFactory.getFhirBaseUrl()
					+ "' referenced in Task with id '" + taskId + "'");

		if (documentReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReferences for project-identifier '{}' referenced in Task with id '{}', using first ({})",
					documentReferences.size(), projectIdentifier, taskId,
					documentReferences.get(0).getIdElement().getValue());

		DocumentReference documentReference = documentReferences.get(0);
		dataLogger.logResource("DocumentReference for project-identifier '" + projectIdentifier + "'",
				documentReference);

		return documentReference;
	}

	private Stream<DataResource> readAttachments(DocumentReference documentReference, String taskId)
	{
		return Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(a, documentReference.getIdElement(), taskId));
	}

	private DataResource readAttachment(Attachment attachment, IdType documentReferenceId, String taskId)
	{
		String url = getAttachmentUrl(attachment, documentReferenceId, taskId);
		IdType urlIdType = checkValidKdsFhirStoreUrlAndGetIdType(url, documentReferenceId, taskId);

		if (fhirBinaryStreamReadEnabled && ResourceType.Binary.name().equals(urlIdType.getResourceType()))
		{
			String mimetype = getAttachmentMimeType(attachment, documentReferenceId, taskId);
			InputStream stream = fhirClientFactory.getBinaryStreamFhirClient().read(urlIdType, mimetype);
			return DataResource.of(stream, mimetype);
		}
		else
		{
			Resource resource = fhirClientFactory.getStandardFhirClient().read(urlIdType);
			return DataResource.of(resource);
		}
	}

	private String getAttachmentUrl(Attachment attachment, IdType documentReferenceId, String taskId)
	{
		return Optional.of(attachment).filter(Attachment::hasUrl).map(Attachment::getUrl).orElseThrow(
				() -> new IllegalArgumentException("Could not find any attachment URLs in DocumentReference with id '"
						+ getKdsFhirStoreAbsoluteId(documentReferenceId) + "' belonging to task with id '" + taskId
						+ "'"));
	}

	private String getAttachmentMimeType(Attachment attachment, IdType documentReferenceId, String taskId)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType)
				.orElseThrow(() -> new IllegalArgumentException(
						"Could not find any attachment contentType (mimeType) in DocumentReference with id '"
								+ getKdsFhirStoreAbsoluteId(documentReferenceId) + "' belonging to task with id '"
								+ taskId + "'"));
	}

	private IdType checkValidKdsFhirStoreUrlAndGetIdType(String url, IdType documentReferenceId, String taskId)
	{
		try
		{
			IdType idType = new IdType(url);
			String fhirBaseUrl = fhirClientFactory.getFhirBaseUrl();

			// expecting no Base URL or, Base URL equal to KDS client Base URL
			boolean hasValidBaseUrl = !idType.hasBaseUrl() || fhirBaseUrl.equals(idType.getBaseUrl());
			boolean isResourceReference = idType.hasResourceType() && idType.hasIdPart();

			if (hasValidBaseUrl && isResourceReference)
				return idType;
			else
				throw new IllegalArgumentException("Attachment URL " + url + " in DocumentReference with id '"
						+ getKdsFhirStoreAbsoluteId(documentReferenceId) + "' belonging to task with id '" + taskId
						+ "' is not a valid KDS FHIR store reference (baseUrl if not empty must match '" + fhirBaseUrl
						+ "', resource type must be set, id must be set)");
		}
		catch (Exception exception)
		{
			logger.warn("Could not check if attachment url is a valid KDS FHIR store url - {}", exception.getMessage());
			throw new RuntimeException(
					"Could not check if attachment url is a valid KDS FHIR store url - " + exception.getMessage(),
					exception);
		}
	}

	private List<Resource> getResources(Stream<DataResource> dataResources, String projectIdentifier)
	{
		return dataResources.map(this::getResource).filter(Objects::nonNull).peek(
				r -> dataLogger.logResource("Read attachment for project-identifier '" + projectIdentifier + "'", r))
				.toList();
	}

	private Resource getResource(DataResource attachment)
	{
		// TODO handle stream directly and do not parse to resource
		if (attachment.hasInputStream())
		{
			try (InputStream in = attachment.stream)
			{
				return new Binary().setData(in.readAllBytes()).setContentType(attachment.mimetype);
			}
			catch (IOException exception)
			{
				throw new RuntimeException(
						"Could not read Binary resource contents from stream " + exception.getMessage(), exception);
			}
		}
		else if (attachment.hasResource())
			return attachment.resource;
		else
			throw new RuntimeException("Binary not available as resource or stream");
	}

	private String getKdsFhirStoreAbsoluteId(IdType idType)
	{
		return new IdType(fhirClientFactory.getFhirBaseUrl(), idType.getResourceType(), idType.getIdPart(),
				idType.getVersionIdPart()).getValue();
	}
}
