package de.medizininformatik_initiative.process.data_transfer.message;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.activity.RetryTaskSender;
import de.medizininformatik_initiative.processes.common.error.MessageEndEventErrorHandlerWithTaskOutput;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;

import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.MessageEndEvent;
import dev.dsf.bpe.v2.activity.task.TaskSender;
import dev.dsf.bpe.v2.activity.values.SendTaskValues;
import dev.dsf.bpe.v2.error.MessageEndEventErrorHandler;
import dev.dsf.bpe.v2.variables.Target;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public class SendReceipt implements MessageEndEvent, InitializingBean
{
	private final ProcessPluginApi api;
	private final DataSetStatusGenerator statusGenerator;

	public SendReceipt(ProcessPluginApi api, DataSetStatusGenerator statusGenerator)
	{
		this.api = api;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public List<Task.ParameterComponent> getAdditionalInputParameters(ProcessPluginApi api, Variables variables,
			SendTaskValues sendTaskValues, Target target)
	{
		if (variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR) != null)
			return createReceiptError(variables);
		else
			return createReceiptOk();
	}

	private List<Task.ParameterComponent> createReceiptError(Variables variables)
	{
		return statusGenerator.transformOutputToInputComponent(variables.getStartTask(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER, api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS).stream()
				.map(this::receiveToReceiptStatus).toList();
	}

	private Task.ParameterComponent receiveToReceiptStatus(Task.ParameterComponent parameterComponent)
	{
		Type value = parameterComponent.getValue();
		if (value instanceof Coding coding
				&& ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR.equals(coding.getCode()))
		{
			coding.setCode(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_ERROR);
		}

		parameterComponent.getExtensionsByUrl(ConstantsBase.EXTENSION_DATA_SET_STATUS_ERROR_URL).stream()
				.map(Extension::getValue).filter(StringType.class::isInstance).map(StringType.class::cast)
				.forEach(v -> v.setValue(v.getValue().split(ConstantsBase.EXCEPTION_MESSAGE_DIVIDER, 2)[0]));

		return parameterComponent;
	}

	private List<Task.ParameterComponent> createReceiptOk()
	{
		return List.of(statusGenerator.createDataSetStatusInput(api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK,
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER, api.getProcessPluginDefinition().getResourceVersion(),
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));
	}

	@Override
	public TaskSender getTaskSender(ProcessPluginApi api, Variables variables, SendTaskValues sendTaskValues)
	{
		return new RetryTaskSender(api, variables, sendTaskValues, getBusinessKeyStrategy(),
				(target) -> getAdditionalInputParameters(api, variables, sendTaskValues, target));
	}

	@Override
	public MessageEndEventErrorHandler getErrorHandler()
	{
		return new MessageEndEventErrorHandlerWithTaskOutput(getOutputGenerator());
	}

	private Function<Exception, Task.TaskOutputComponent> getOutputGenerator()
	{
		return (exception) ->
		{
			String statusCode = ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_REACHABLE;
			if (exception instanceof WebApplicationException webApplicationException
					&& webApplicationException.getResponse() != null
					&& webApplicationException.getResponse().getStatus() == Response.Status.FORBIDDEN.getStatusCode())
			{
				statusCode = ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_ALLOWED;
			}

			return statusGenerator.createDataSetStatusOutput(api.getProcessPluginDefinition().getResourceVersion(),
					statusCode, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					api.getProcessPluginDefinition().getResourceVersion(),
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Send receipt failed");
		};
	}
}
