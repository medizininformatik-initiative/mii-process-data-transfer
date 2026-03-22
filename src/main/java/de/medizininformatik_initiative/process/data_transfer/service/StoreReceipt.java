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
import dev.dsf.bpe.v2.variables.Variables;

public class StoreReceipt implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(StoreReceipt.class);

	private final DataSetStatusGenerator statusGenerator;
	private final boolean dicEmailEnabled;

	public StoreReceipt(DataSetStatusGenerator statusGenerator, boolean dicEmailEnabled)
	{
		this.statusGenerator = statusGenerator;
		this.dicEmailEnabled = dicEmailEnabled;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String consortiumIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		Task startTask = variables.getStartTask();
		Task currentTask = variables.getLatestTask();

		if (!currentTask.getId().equals(startTask.getId()))
			handleReceivedResponse(api, startTask, currentTask);
		else if (Task.TaskStatus.INPROGRESS.equals(startTask.getStatus()))
			handleMissingResponse(api, startTask, variables);

		writeStatusLogAndSendMail(api, startTask, projectIdentifier, consortiumIdentifier, dmsIdentifier,
				dicEmailEnabled);

		variables.updateTask(startTask);
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
			String consortiumIdentifier, String dmsIdentifier, boolean dicEmailEnabled)
	{
		startTask.getOutput().stream().filter(o -> o.getValue() instanceof Coding)
				.filter(o -> ConstantsBase.CODESYSTEM_DATA_SET_STATUS.equals(((Coding) o.getValue()).getSystem()))
				.forEach(o -> doWriteStatusLogAndSendMail(api, o, startTask, projectIdentifier, consortiumIdentifier,
						dmsIdentifier, dicEmailEnabled));
	}

	private void doWriteStatusLogAndSendMail(ProcessPluginApi api, Task.TaskOutputComponent output, Task task,
			String projectIdentifier, String consortiumIdentifier, String dmsIdentifier, boolean dicEmailEnabled)
	{
		Coding status = (Coding) output.getValue();
		String code = status.getCode();
		String error = output.hasExtension() ? output.getExtensionFirstRep().getValueAsPrimitive().getValueAsString()
				: "none";

		if (ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK.equals(code))
		{
			logger.info(
					"Delivering encrypted data-set for DMS '{}' and project-identifier '{}' has status code '{}' in Task '{}'",
					dmsIdentifier, projectIdentifier, code, api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));
			if (dicEmailEnabled)
				sendSuccessfulMail(api, task, projectIdentifier, consortiumIdentifier, dmsIdentifier, code);
		}
		else
		{
			String errorLog = error.isBlank() ? "" : " - " + error;
			logger.warn("Could not deliver encrypted data-set for DMS '{}' and project-identifier '{}' in Task '{}'{}",
					dmsIdentifier, projectIdentifier, api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task),
					errorLog);
			if (dicEmailEnabled)
				sendErrorMail(api, task, projectIdentifier, consortiumIdentifier, dmsIdentifier, code, error);
		}
	}

	private void sendSuccessfulMail(ProcessPluginApi api, Task task, String projectIdentifier,
			String consortiumIdentifier, String dmsIdentifier, String code)
	{
		String subject = "Data-set successfully delivered in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "A data-set has been successfully delivered and retrieved in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' and Task '"
				+ api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' to/from DMS '" + consortiumIdentifier
				+ "|" + dmsIdentifier + "' regarding project-identifier '" + projectIdentifier + "' with status code '"
				+ code + "'";

		api.getMailService().send(subject, message);
	}

	private void sendErrorMail(ProcessPluginApi api, Task task, String projectIdentifier, String consortiumIdentifier,
			String dmsIdentifier, String code, String error)
	{
		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "Could not download, decrypt, validate or insert data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' and Task '"
				+ api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' at DMS '" + consortiumIdentifier + "|"
				+ dmsIdentifier + "' regarding project-identifier '" + projectIdentifier + "':\n" + "- status code: "
				+ code + "\n" + "- error: " + error;

		api.getMailService().send(subject, message);
	}
}
