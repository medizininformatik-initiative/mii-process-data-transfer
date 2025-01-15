package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Coding;
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

public class StoreReceipt extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(StoreReceipt.class);

	private final DataSetStatusGenerator statusGenerator;

	public StoreReceipt(ProcessPluginApi api, DataSetStatusGenerator statusGenerator)
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
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		Task startTask = variables.getStartTask();
		Task currentTask = variables.getLatestTask();

		if (!currentTask.getId().equals(startTask.getId()))
			handleReceivedResponse(startTask, currentTask);
		else if (Task.TaskStatus.INPROGRESS.equals(startTask.getStatus()))
			handleMissingResponse(startTask);

		writeStatusLogAndSendMail(startTask, projectIdentifier, dmsIdentifier);

		variables.updateTask(startTask);
		if (Task.TaskStatus.FAILED.equals(startTask.getStatus()))
			updateTaskOnServer(startTask);
	}

	private void handleReceivedResponse(Task startTask, Task currentTask)
	{
		statusGenerator.transformInputToOutput(currentTask, startTask, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS);

		if (startTask.getOutput().stream().filter(Task.TaskOutputComponent::hasExtension)
				.flatMap(o -> o.getExtension().stream())
				.anyMatch(e -> ConstantsBase.EXTENSION_DATA_SET_STATUS_ERROR_URL.equals(e.getUrl())))
			startTask.setStatus(Task.TaskStatus.FAILED);
	}

	private void handleMissingResponse(Task startTask)
	{
		startTask.setStatus(Task.TaskStatus.FAILED);
		startTask.addOutput(statusGenerator.createDataSetStatusOutput(
				ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_MISSING,
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));
	}

	private void writeStatusLogAndSendMail(Task startTask, String projectIdentifier, String dmsIdentifier)
	{
		startTask.getOutput().stream().filter(o -> o.getValue() instanceof Coding)
				.filter(o -> ConstantsBase.CODESYSTEM_DATA_SET_STATUS.equals(((Coding) o.getValue()).getSystem()))
				.forEach(o -> doWriteStatusLogAndSendMail(o, startTask, projectIdentifier, dmsIdentifier));
	}

	private void doWriteStatusLogAndSendMail(Task.TaskOutputComponent output, Task task, String projectIdentifier,
			String dmsIdentifier)
	{
		Coding status = (Coding) output.getValue();
		String code = status.getCode();
		String error = output.hasExtension() ? output.getExtensionFirstRep().getValueAsPrimitive().getValueAsString()
				: "none";

		if (ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK.equals(code))
		{
			logger.info(
					"Task with id '{}' for project-identifier '{}' and DMS with identifier '{}' has data-set status code '{}'",
					task, projectIdentifier, dmsIdentifier, code);

			sendSuccessfulMail(task, projectIdentifier, dmsIdentifier, code);
		}
		else
		{
			String errorLog = error.isBlank() ? "" : " - " + error;
			logger.warn(
					"Could not deliver encrypted data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'{}",
					dmsIdentifier, projectIdentifier, task.getId(), errorLog);

			sendErrorMail(task, projectIdentifier, dmsIdentifier, code, error);
		}
	}

	private void sendSuccessfulMail(Task task, String projectIdentifier, String dmsIdentifier, String code)
	{
		String subject = "Data-set successfully delivered in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "A data-set has been successfully delivered and retrieved in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' for Task with id '" + task.getId()
				+ "' to/from DMS with identifier '" + dmsIdentifier + "' for project-identifier '" + projectIdentifier
				+ "' with status code '" + code + "'";

		api.getMailService().send(subject, message);
	}

	private void sendErrorMail(Task task, String projectIdentifier, String dmsIdentifier, String code, String error)
	{
		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "Could not download, decrypt, validate or insert data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' for Task with id '" + task.getId()
				+ "' at DMS with identifier '" + dmsIdentifier + "' for project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + code + "\n" + "- error: " + error;

		api.getMailService().send(subject, message);
	}

	private void updateTaskOnServer(Task startTask)
	{
		api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.update(startTask);
	}
}
