package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

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

public class HandleErrorReceive implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(HandleErrorReceive.class);

	private final DataSetStatusGenerator statusGenerator;

	public HandleErrorReceive(DataSetStatusGenerator statusGenerator)
	{
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		Task task = variables.getStartTask();
		String errorCode = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR);
		String errorMessage = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE);

		task.setStatus(Task.TaskStatus.FAILED);
		task.addOutput(statusGenerator.createDataSetStatusOutput(api.getProcessPluginDefinition().getResourceVersion(),
				errorCode, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, errorMessage));
		variables.updateTask(task);

		logger.warn("Error in Process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE
				+ "' for project-identifier '{}' to DMS with identifier '{}' referenced in Task with id '{}' - {}",
				variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER),
				variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER), task.getId(),
				errorMessage);

		sendMail(api, task, variables, errorMessage);
	}

	private void sendMail(ProcessPluginApi api, Task task, Variables variables, String errorMessage)
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		String message = "Could not download data in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE
				+ "' for Task with id '" + task.getId() + "' from organization '"
				+ task.getRequester().getIdentifier().getValue() + "' for project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR + "\n"
				+ "- error: " + (errorMessage == null ? "none" : errorMessage);

		api.getMailService().send(subject, message);
	}
}
