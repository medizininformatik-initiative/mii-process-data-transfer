package de.medizininformatik_initiative.process.data_transfer.message;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.activity.RetryTaskSender;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.MessageEndEvent;
import dev.dsf.bpe.v2.activity.task.TaskSender;
import dev.dsf.bpe.v2.activity.values.SendTaskValues;
import dev.dsf.bpe.v2.variables.Target;
import dev.dsf.bpe.v2.variables.Variables;

public class SendReceipt implements MessageEndEvent, InitializingBean
{
	private final DataSetStatusGenerator statusGenerator;

	public SendReceipt(DataSetStatusGenerator statusGenerator)
	{
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public List<Task.ParameterComponent> getAdditionalInputParameters(ProcessPluginApi api, Variables variables,
			SendTaskValues sendTaskValues, Target target)
	{
		if (variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR) != null)
			return createReceiptError(api, variables);
		else
			return createReceiptOk(api);
	}

	@Override
	public TaskSender getTaskSender(ProcessPluginApi api, Variables variables, SendTaskValues sendTaskValues)
	{
		return new RetryTaskSender(api, variables, sendTaskValues, getBusinessKeyStrategy(),
				(target) -> getAdditionalInputParameters(api, variables, sendTaskValues, target));
	}

	private List<Task.ParameterComponent> createReceiptError(ProcessPluginApi api, Variables variables)
	{
		return statusGenerator.transformOutputToInputComponent(variables.getStartTask(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER, api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS).map(this::receiveToReceiptStatus)
				.toList();
	}

	private Task.ParameterComponent receiveToReceiptStatus(Task.ParameterComponent parameterComponent)
	{
		Type value = parameterComponent.getValue();
		if (value instanceof Coding coding
				&& ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR.equals(coding.getCode()))
		{
			coding.setCode(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_ERROR);
		}

		return parameterComponent;
	}

	private List<Task.ParameterComponent> createReceiptOk(ProcessPluginApi api)
	{
		return List.of(statusGenerator.createDataSetStatusInput(api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK,
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER, api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));
	}
}
