package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
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
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;
import dev.dsf.fhir.client.BasicFhirWebserviceClient;
import jakarta.ws.rs.core.MediaType;

public class DownloadData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DownloadData.class);

	private final DataSetStatusGenerator statusGenerator;
	private final DataLogger dataLogger;
	private final boolean fhirBinaryStreamWriteEnabled;

	public DownloadData(ProcessPluginApi api, DataSetStatusGenerator statusGenerator,
			boolean fhirBinaryStreamWriteEnabled, DataLogger dataLogger)
	{
		super(api);
		this.statusGenerator = statusGenerator;
		this.fhirBinaryStreamWriteEnabled = fhirBinaryStreamWriteEnabled;
		this.dataLogger = dataLogger;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(statusGenerator, "statusGenerator");
		Objects.requireNonNull(dataLogger, "dataLogger");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String sendingOrganization = task.getRequester().getIdentifier().getValue();

		String projectIdentifier = getProjectIdentifier(task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);

		IdType documentReferenceLocation = getDocumentReferenceLocation(task, sendingOrganization, projectIdentifier);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
				documentReferenceLocation.getValue());

		logger.info(
				"Downloading data-set from organization '{}' for project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				sendingOrganization, projectIdentifier, task.getId(), documentReferenceLocation.getValue());

		try
		{
			DocumentReference documentReference = readDocumentReference(documentReferenceLocation, sendingOrganization,
					projectIdentifier, task.getId());
			Stream<DataResource> attachments = readAttachments(documentReference, task.getId());
			List<Resource> resources = getResources(attachments, sendingOrganization, projectIdentifier, task.getId());

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE,
					documentReference);
			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, resources);
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Download data-set failed"));
			variables.updateTask(task);

			logger.warn(
					"Could not download data-set from organization '{}' for project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments) - {}",
					sendingOrganization, projectIdentifier, task.getId(), documentReferenceLocation.getValue(),
					exception.getMessage());

			String error = "Download data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private String getProjectIdentifier(Task task)
	{
		return task.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER.equals(c.getCode())))
				.filter(i -> i.getValue() instanceof Identifier).map(i -> (Identifier) i.getValue())
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).findFirst()
				.orElseThrow(() -> new RuntimeException("No project-identifier present in Task.input"));
	}

	private IdType getDocumentReferenceLocation(Task task, String sendingOrganization, String projectIdentifier)
	{
		List<String> dataSetReferences = api.getTaskHelper()
				.getInputParameters(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION,
						Reference.class)
				.map(Task.ParameterComponent::getValue).filter(i -> i instanceof Reference).map(i -> (Reference) i)
				.filter(Reference::hasReference).map(Reference::getReference).toList();

		if (dataSetReferences.size() < 1)
			throw new IllegalArgumentException("No DocumentReference reference present in Task.input");

		if (dataSetReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReference references from organization '{}' for project-identifier '{}' referenced in Task with id '{}', using only the first",
					dataSetReferences.size(), sendingOrganization, projectIdentifier, task.getId());

		return new IdType(dataSetReferences.get(0));
	}

	private DocumentReference readDocumentReference(IdType documentReferenceLocation, String sendingOrganization,
			String projectIdentifier, String taskId)
	{
		DocumentReference documentReference = api.getFhirWebserviceClientProvider()
				.getWebserviceClient(documentReferenceLocation.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.read(DocumentReference.class, documentReferenceLocation.getIdPart(),
						documentReferenceLocation.getVersionIdPart());

		dataLogger
				.logResource(
						"DocumentReference from organization '" + sendingOrganization + "' for project-identifier '"
								+ projectIdentifier + "' referenced in Task with id '" + taskId + "'",
						documentReference);

		return documentReference;
	}

	private Stream<DataResource> readAttachments(DocumentReference documentReference, String taskId)
	{
		return documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(a, documentReference.getIdElement(), taskId));
	}

	private DataResource readAttachment(Attachment attachment, IdType documentReferenceId, String taskId)
	{
		IdType attachmentId = new IdType(attachment.getUrl());

		BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider()
				.getWebserviceClient(attachmentId.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);

		if (ResourceType.Binary.name().equals(attachmentId.getResourceType()) && fhirBinaryStreamWriteEnabled)
		{
			String mimetype = getAttachmentMimeType(attachment);
			return DataResource.of(attachmentId, mimetype);
		}
		else
		{
			try (InputStream binary = readBinaryResource(client, attachmentId.getIdPart(),
					attachmentId.getVersionIdPart(), attachment.getContentType()))
			{
				return DataResource
						.of(new Binary().setData(binary.readAllBytes()).setContentType(attachment.getContentType()));
			}
			catch (Exception exception)
			{
				String error = "Downloading attachment failed - " + exception.getMessage();
				throw new RuntimeException(error, exception);
			}
		}
	}

	private String getAttachmentMimeType(Attachment attachment)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType)
				.orElseThrow(() -> new IllegalArgumentException(
						"Could not find any attachment contentType (mimeType) in DocumentReference"));
	}

	private InputStream readBinaryResource(BasicFhirWebserviceClient client, String id, String version, String mimeType)
	{
		if (version != null && !version.isEmpty())
			return client.readBinary(id, version, MediaType.valueOf(mimeType));
		else
			return client.readBinary(id, MediaType.valueOf(mimeType));
	}

	private List<Resource> getResources(Stream<DataResource> dataResources, String sendingOrganization,
			String projectIdentifier, String taskId)
	{
		return dataResources.map(this::getResource).filter(Objects::nonNull)
				.peek(r -> dataLogger.logResource(
						"Read attachment from organization '" + sendingOrganization + "' for project-identifier '"
								+ projectIdentifier + "' referenced in Task with id '" + taskId + "'",
						r))
				.toList();
	}

	private Resource getResource(DataResource attachment)
	{
		if (attachment.hasStreamLocation() && fhirBinaryStreamWriteEnabled)
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
