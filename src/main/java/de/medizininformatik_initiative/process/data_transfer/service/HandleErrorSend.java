package de.medizininformatik_initiative.process.data_transfer.service;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Task;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class HandleErrorSend extends AbstractServiceDelegate
{
	public HandleErrorSend(ProcessPluginApi api)
	{
		super(api);
	}

	@Override
	protected void doExecute(DelegateExecution delegateExecution, Variables variables)
	{
		Task task = variables.getStartTask();

		if (Task.TaskStatus.FAILED.equals(task.getStatus()))
			sendMail(task, variables);
	}

	private void sendMail(Task task, Variables variables)
	{
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String error = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR_MESSAGE);

		String statusCode = task.getOutput().stream().filter(o -> o.getValue() instanceof Coding)
				.map(o -> (Coding) o.getValue())
				.filter(c -> ConstantsBase.CODESYSTEM_DATA_SET_STATUS.equals(c.getSystem())).map(c -> c.getCode())
				.findFirst().orElse("unknown");

		String subject = "Error in process '" + ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR + "'";
		String message = "Could not send DocumentReference with attachments in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' for Task with id '" + task.getId()
				+ "' to DMS with identifier '" + dmsIdentifier + "' for project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + statusCode + "\n" + "- error: " + (error == null ? "none" : error);

		api.getMailService().send(subject, message);
	}
}
