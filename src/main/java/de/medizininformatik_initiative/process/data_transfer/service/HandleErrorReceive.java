package de.medizininformatik_initiative.process.data_transfer.service;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Task;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class HandleErrorReceive extends AbstractServiceDelegate
{
	public HandleErrorReceive(ProcessPluginApi api)
	{
		super(api);
	}

	@Override
	protected void doExecute(DelegateExecution delegateExecution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String error = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE);

		sendMail(task, projectIdentifier, error);

		task.setStatus(Task.TaskStatus.FAILED);
		api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.update(task);
		variables.updateTask(task);
	}

	private void sendMail(Task task, String projectIdentifier, String error)
	{
		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		String message = "Could not download and insert new data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' for Task with id '" + task.getId()
				+ "' from organization '" + task.getRequester().getIdentifier().getValue()
				+ "' for project-identifier '" + projectIdentifier + "':\n" + "- status code: "
				+ ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR + "\n" + "- error: "
				+ (error == null ? "none" : error);

		api.getMailService().send(subject, message);
	}
}
