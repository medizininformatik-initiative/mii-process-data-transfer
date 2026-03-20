package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.variables.Variables;

public class StoreReceipt implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(StoreReceipt.class);

	private final DataSetStatusGenerator statusGenerator;

	public StoreReceipt(DataSetStatusGenerator statusGenerator)
	{
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws ErrorBoundaryEvent, Exception
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		Task startTask = variables.getStartTask();
		Task currentTask = variables.getLatestTask();

		if (!currentTask.getId().equals(startTask.getId()))
			handleReceivedResponse(api, startTask, currentTask);
		else if (Task.TaskStatus.INPROGRESS.equals(startTask.getStatus()))
			handleMissingResponse(api, startTask, variables);

		writeStatusLogAndSendMail(api, startTask, projectIdentifier, dmsIdentifier);

		variables.updateTask(startTask);
		if (Task.TaskStatus.FAILED.equals(startTask.getStatus()))
			updateTaskOnServer(api, startTask);
	}

	private void handleReceivedResponse(ProcessPluginApi api, Task startTask, Task currentTask)
	{
		statusGenerator.transformInputToOutput(currentTask, startTask, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS);

		if (startTask.getOutput().stream().filter(Task.TaskOutputComponent::hasExtension)
				.flatMap(o -> o.getExtension().stream())
				.anyMatch(e -> ConstantsBase.EXTENSION_DATA_SET_STATUS_ERROR_URL.equals(e.getUrl())))
			startTask.setStatus(Task.TaskStatus.FAILED);
	}

	private void handleMissingResponse(ProcessPluginApi api, Task startTask, Variables variables)
	{
		// only add receipt-missing if data could be sent to DMS
		if (variables.getVariable(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR) == null)
		{
			startTask.setStatus(Task.TaskStatus.FAILED);
			startTask.addOutput(
					statusGenerator.createDataSetStatusOutput(api.getProcessPluginDefinition().getResourceVersion(),
							ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_MISSING,
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
							api.getProcessPluginDefinition().getResourceVersion(),
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));
		}
	}

	private void writeStatusLogAndSendMail(ProcessPluginApi api, Task startTask, String projectIdentifier,
			String dmsIdentifier)
	{
		startTask.getOutput().stream().filter(o -> o.getValue() instanceof Coding)
				.filter(o -> ConstantsBase.CODESYSTEM_DATA_SET_STATUS.equals(((Coding) o.getValue()).getSystem()))
				.forEach(o -> doWriteStatusLogAndSendMail(api, o, startTask, projectIdentifier, dmsIdentifier));
	}

	private void doWriteStatusLogAndSendMail(ProcessPluginApi api, Task.TaskOutputComponent output, Task task,
			String projectIdentifier, String dmsIdentifier)
	{
		Coding status = (Coding) output.getValue();
		String code = status.getCode();
		String error = output.hasExtension() ? output.getExtensionFirstRep().getValueAsPrimitive().getValueAsString()
				: "none";

		if (ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK.equals(code))
		{
			logger.info("Task with id '{}' for DMS '{}' and project-identifier '{}' has data-set status code '{}'",
					task.getId(), dmsIdentifier, projectIdentifier, code);

			sendSuccessfulMail(api, task, projectIdentifier, dmsIdentifier, code);
		}
		else
		{
			String errorLog = error.isBlank() ? "" : " - " + error;
			logger.warn(
					"Could not deliver encrypted data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'{}",
					dmsIdentifier, projectIdentifier, task.getId(), errorLog);

			sendErrorMail(api, task, projectIdentifier, dmsIdentifier, code, error);
		}
	}

	private void sendSuccessfulMail(ProcessPluginApi api, Task task, String projectIdentifier, String dmsIdentifier,
			String code)
	{
		String subject = "Data-set successfully delivered in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "A data-set has been successfully delivered and retrieved in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' for Task with id '" + task.getId()
				+ "' to/from DMS with identifier '" + dmsIdentifier + "' for project-identifier '" + projectIdentifier
				+ "' with status code '" + code + "'";

		api.getMailService().send(subject, message);
	}

	private void sendErrorMail(ProcessPluginApi api, Task task, String projectIdentifier, String dmsIdentifier,
			String code, String error)
	{
		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "Could not download, decrypt, validate or insert data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' for Task with id '" + task.getId()
				+ "' at DMS with identifier '" + dmsIdentifier + "' for project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + code + "\n" + "- error: " + error;

		api.getMailService().send(subject, message);
	}

	private void updateTaskOnServer(ProcessPluginApi api, Task startTask)
	{
		api.getDsfClientProvider().getLocal().withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
				DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)).update(startTask);
	}
}
