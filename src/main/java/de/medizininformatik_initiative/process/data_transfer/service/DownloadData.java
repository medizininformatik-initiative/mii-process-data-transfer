package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
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

	public DownloadData(ProcessPluginApi api, DataSetStatusGenerator statusGenerator, DataLogger dataLogger)
	{
		super(api);
		this.statusGenerator = statusGenerator;
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

		IdType documentReferenceLocation = getDocumentReferenceLocation(task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
				documentReferenceLocation.getValue());

		logger.info(
				"Downloading data-set from organization '{}' for project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				sendingOrganization, projectIdentifier, task.getId(), documentReferenceLocation.getValue());

		try
		{
			DocumentReference documentReference = readDocumentReference(documentReferenceLocation, sendingOrganization,
					projectIdentifier);
			List<Binary> encryptedResources = readAttachments(documentReference, sendingOrganization,
					projectIdentifier);

			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE,
					documentReference);
			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, encryptedResources);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
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
				.map(Identifier::getValue).findFirst().orElseThrow(() -> new RuntimeException(
						"No project-identifier present in Task with id '" + task.getId() + "'"));
	}

	private IdType getDocumentReferenceLocation(Task task)
	{
		List<String> dataSetReferences = api.getTaskHelper()
				.getInputParameters(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION,
						Reference.class)
				.map(Task.ParameterComponent::getValue).filter(i -> i instanceof Reference).map(i -> (Reference) i)
				.filter(Reference::hasReference).map(Reference::getReference).toList();

		if (dataSetReferences.size() < 1)
			throw new IllegalArgumentException(
					"No DocumentReference location present in Task with id '" + task.getId() + "'");

		if (dataSetReferences.size() > 1)
			logger.warn("Found {} DocumentReference locations in Task with id '{}', using only the first",
					dataSetReferences.size(), task.getId());

		return new IdType(dataSetReferences.get(0));
	}

	private DocumentReference readDocumentReference(IdType documentReferenceLocation, String sendingOrganization,
			String projectIdentifier)
	{
		DocumentReference documentReference = api.getFhirWebserviceClientProvider()
				.getWebserviceClient(documentReferenceLocation.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.read(DocumentReference.class, documentReferenceLocation.getIdPart(),
						documentReferenceLocation.getVersionIdPart());

		dataLogger.logResource("DocumentReference from organization '" + sendingOrganization
				+ "' for project-identifier '" + projectIdentifier + "' ", documentReference);

		return documentReference;
	}

	private List<Binary> readAttachments(DocumentReference documentReference, String sendingOrganization,
			String projectIdentifier)
	{
		return documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).map(this::readAttachment)
				.peek(a -> dataLogger.logResource("Attachment from organization '" + sendingOrganization
						+ "' for project-identifier '" + projectIdentifier + "'", a))
				.toList();
	}

	private Binary readAttachment(Attachment attachment)
	{
		IdType attachmentId = new IdType(attachment.getUrl());
		BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider()
				.getWebserviceClient(attachmentId.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);

		// TODO handle stream directly and do not parse to resource
		try (InputStream binary = readBinaryResource(client, attachmentId.getIdPart(), attachmentId.getVersionIdPart(),
				attachment.getContentType()))
		{
			return new Binary().setData(binary.readAllBytes()).setContentType(attachment.getContentType());
		}
		catch (IOException exception)
		{
			throw new RuntimeException(exception);
		}
	}

	private InputStream readBinaryResource(BasicFhirWebserviceClient client, String id, String version, String mimeType)
	{
		if (version != null && !version.isEmpty())
			return client.readBinary(id, version, MediaType.valueOf(mimeType));
		else
			return client.readBinary(id, MediaType.valueOf(mimeType));
	}
}
