package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

import org.hl7.fhir.r4.model.Task;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.variables.Variables;

public class HandleErrorReceive implements ServiceTask, InitializingBean
{
	private final DataSetStatusGenerator statusGenerator;
	private final boolean dmsEmailEnabled;

	public HandleErrorReceive(DataSetStatusGenerator statusGenerator, boolean dmsEmailEnabled)
	{
		this.statusGenerator = statusGenerator;
		this.dmsEmailEnabled = dmsEmailEnabled;
	}

	@Override
	public void afterPropertiesSet() throws Exception
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

		if (dmsEmailEnabled)
			sendMail(api, variables, task, errorMessage);

		failAndAddOutputTask(api, task, errorCode, errorMessage, variables);
	}

	private void sendMail(ProcessPluginApi api, Variables variables, Task task, String error)
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		String message = "Could not download, decrypt, validate or insert data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' and Task '"
				+ api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' from organization '"
				+ task.getRequester().getIdentifier().getValue() + "' and project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR + "\n"
				+ "- error: " + (error == null ? "none" : error);

		api.getMailService().send(subject, message);
	}

	private void failAndAddOutputTask(ProcessPluginApi api, Task task, String errorCode, String errorMessage, Variables variables)
	{
		task.setStatus(Task.TaskStatus.FAILED);
		task.addOutput(statusGenerator.createDataSetStatusOutput(api.getProcessPluginDefinition().getResourceVersion(),
				errorCode, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, errorMessage));
		variables.updateTask(task);

		// Failed tasks are not automatically updated on process end listener
		api.getDsfClientProvider().getLocal().withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
				DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)).update(task);
	}
}
