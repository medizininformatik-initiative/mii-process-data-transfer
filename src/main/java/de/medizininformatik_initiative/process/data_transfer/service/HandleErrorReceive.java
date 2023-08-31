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

		if (Task.TaskStatus.FAILED.equals(task.getStatus()))
		{
			sendMail(task, variables);
			api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
					.update(task);
		}
	}

	private void sendMail(Task task, Variables variables)
	{
		String error = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE);

		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		String message = "Could not download and insert new data-set in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' from organization '"
				+ task.getRequester().getIdentifier().getValue() + "' in Task with id '" + task.getId() + "':\n"
				+ "- status code: " + ConstantsDataTransfer.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR + "\n"
				+ "- error: " + (error == null ? "none" : error);

		api.getMailService().send(subject, message);
	}
}
