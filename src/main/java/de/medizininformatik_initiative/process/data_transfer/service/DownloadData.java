package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
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

	public DownloadData(ProcessPluginApi api, DataSetStatusGenerator statusGenerator)
	{
		super(api);
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String sendingOrganization = task.getRequester().getIdentifier().getValue();

		String projectIdentifier = getProjectIdentifier(task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);

		IdType dataSetReference = getDataSetReference(task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE,
				dataSetReference.getValue());

		logger.info(
				"Downloading data-set with id '{}' from organization '{}' for project-identifier '{}' referenced in Task with id '{}'",
				dataSetReference.getValue(), sendingOrganization, projectIdentifier, task.getId());

		try
		{
			byte[] bundleEncrypted = readDataSet(dataSetReference);
			variables.setByteArray(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED, bundleEncrypted);
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
					"Could not download data-set with id '{}' from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dataSetReference.getValue(), sendingOrganization, projectIdentifier, task.getId(),
					exception.getMessage());

			String error = "Download data-set failed - " + exception.getMessage();
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE, error);
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

	private IdType getDataSetReference(Task task)
	{
		List<String> dataSetReferences = api.getTaskHelper()
				.getInputParameters(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_REFERENCE, Reference.class)
				.map(Task.ParameterComponent::getValue).filter(i -> i instanceof Reference).map(i -> (Reference) i)
				.filter(Reference::hasReference).map(Reference::getReference).toList();

		if (dataSetReferences.size() < 1)
			throw new IllegalArgumentException("No data-set reference present in Task with id '" + task.getId() + "'");

		if (dataSetReferences.size() > 1)
			logger.warn("Found {} data-set references in Task with id '{}', using only the first",
					dataSetReferences.size(), task.getId());

		return new IdType(dataSetReferences.get(0));
	}

	private byte[] readDataSet(IdType dataSetReference)
	{
		BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider()
				.getWebserviceClient(dataSetReference.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);

		try (InputStream binary = readBinaryResource(client, dataSetReference.getIdPart(),
				dataSetReference.getVersionIdPart()))
		{
			return binary.readAllBytes();
		}
		catch (IOException exception)
		{
			throw new RuntimeException(exception);
		}
	}

	private InputStream readBinaryResource(BasicFhirWebserviceClient client, String id, String version)
	{
		if (version != null && !version.isEmpty())
			return client.readBinary(id, version, MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM));
		else
			return client.readBinary(id, MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM));
	}
}
