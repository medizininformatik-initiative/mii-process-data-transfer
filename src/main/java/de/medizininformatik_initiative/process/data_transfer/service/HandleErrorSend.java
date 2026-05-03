package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Task;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

public class HandleErrorSend implements ServiceTask, InitializingBean
{
	private final DataSetStatusGenerator statusGenerator;
	private final boolean dicEmailEnabled;

	public HandleErrorSend(DataSetStatusGenerator statusGenerator, boolean dicEmailEnabled)
	{
		this.statusGenerator = statusGenerator;
		this.dicEmailEnabled = dicEmailEnabled;
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
		String errorCode = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR);
		String errorMessage = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR_MESSAGE);

		task.setStatus(Task.TaskStatus.FAILED);
		task.addOutput(statusGenerator.createDataSetStatusOutput(api.getProcessPluginDefinition().getResourceVersion(),
				errorCode, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
				api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, errorMessage));
		variables.updateTask(task);

		if (dicEmailEnabled)
			sendMail(api, task, variables, errorMessage);
	}

	private void sendMail(ProcessPluginApi api, Task task, Variables variables, String errorMessage)
	{
		String consortiumIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		String statusCode = task.getOutput().stream().filter(o -> o.getValue() instanceof Coding)
				.map(o -> (Coding) o.getValue())
				.filter(c -> ConstantsBase.CODESYSTEM_DATA_SET_STATUS.equals(c.getSystem())).map(Coding::getCode)
				.findFirst().orElse("unknown");

		String subject = "Error in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "Could not provide data-set in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND
				+ "' and Task '" + api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' for DMS '"
				+ consortiumIdentifier + "|" + dmsIdentifier + "' regarding project-identifier '" + projectIdentifier
				+ "':\n" + "- status code: " + statusCode + "\n" + "- error: "
				+ (errorMessage == null ? "none" : errorMessage);

		api.getMailService().send(subject, message);
	}
}
