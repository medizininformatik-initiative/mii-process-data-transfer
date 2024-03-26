package de.medizininformatik_initiative.process.data_transfer.message;

import java.util.Objects;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractTaskMessageSend;
import dev.dsf.bpe.v1.variables.Variables;
import dev.dsf.fhir.client.FhirWebserviceClient;

public class SendReceipt extends AbstractTaskMessageSend implements InitializingBean
{
	private final DataSetStatusGenerator statusGenerator;

	public SendReceipt(ProcessPluginApi api, DataSetStatusGenerator statusGenerator)
	{
		super(api);
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected Stream<Task.ParameterComponent> getAdditionalInputParameters(DelegateExecution execution,
			Variables variables)
	{
		if (variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR) != null)
			return createReceiptError(variables);
		else
			return createReceiptOk();
	}

	@Override
	protected IdType doSend(FhirWebserviceClient client, Task task)
	{
		return client.withMinimalReturn()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.create(task);
	}

	private Stream<Task.ParameterComponent> createReceiptError(Variables variables)
	{
		return statusGenerator
				.transformOutputToInputComponent(variables.getStartTask(),
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS)
				.map(this::receiveToReceiptStatus);
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

	private Stream<Task.ParameterComponent> createReceiptOk()
	{
		Task.ParameterComponent parameterComponent = new Task.ParameterComponent();
		parameterComponent.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS);
		parameterComponent.setValue(new Coding().setSystem(ConstantsBase.CODESYSTEM_DATA_SET_STATUS)
				.setCode(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK));

		return Stream.of(parameterComponent);
	}
}
