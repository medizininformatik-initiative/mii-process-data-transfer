package de.medizininformatik_initiative.process.data_transfer.util;

import java.util.stream.Stream;

import org.hl7.fhir.r4.model.BackboneElement;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.ParameterComponent;
import org.hl7.fhir.r4.model.Task.TaskOutputComponent;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;

public class DataSetStatusGenerator
{
	public ParameterComponent createDataSetStatusInput(String statusCode)
	{
		return createDataSetStatusInput(statusCode, null);
	}

	public ParameterComponent createDataSetStatusInput(String statusCode, String errorMessage)
	{
		ParameterComponent input = new ParameterComponent();
		input.setValue(new Coding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_SET_STATUS).setCode(statusCode));
		input.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS);

		if (errorMessage != null)
			addErrorExtension(input, errorMessage);

		return input;
	}

	public TaskOutputComponent createDataSetStatusOutput(String statusCode)
	{
		return createDataSetStatusOutput(statusCode, null);
	}

	public TaskOutputComponent createDataSetStatusOutput(String statusCode, String errorMessage)
	{
		TaskOutputComponent output = new TaskOutputComponent();
		output.setValue(new Coding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_SET_STATUS).setCode(statusCode));
		output.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS);

		if (errorMessage != null)
			addErrorExtension(output, errorMessage);

		return output;
	}

	private void addErrorExtension(BackboneElement element, String errorMessage)
	{
		element.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_DATA_SET_STATUS_ERROR_URL)
				.setValue(new StringType(errorMessage));
	}

	public void transformInputToOutput(Task inputTask, Task outputTask)
	{
		transformInputToOutputComponents(inputTask).forEach(outputTask::addOutput);
	}

	public Stream<TaskOutputComponent> transformInputToOutputComponents(Task inputTask)
	{
		return inputTask.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS.equals(c.getCode())))
				.map(this::toTaskOutputComponent);
	}

	private TaskOutputComponent toTaskOutputComponent(ParameterComponent inputComponent)
	{
		TaskOutputComponent outputComponent = new TaskOutputComponent().setType(inputComponent.getType())
				.setValue(inputComponent.getValue().copy());
		outputComponent.setExtension(inputComponent.getExtension());

		return outputComponent;
	}

	public void transformOutputToInput(Task outputTask, Task inputTask)
	{
		transformOutputToInputComponent(outputTask).forEach(inputTask::addInput);
	}

	public Stream<ParameterComponent> transformOutputToInputComponent(Task outputTask)
	{
		return outputTask.getOutput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS.equals(c.getCode())))
				.map(this::toTaskInputComponent);
	}

	private ParameterComponent toTaskInputComponent(TaskOutputComponent outputComponent)
	{
		ParameterComponent inputComponent = new ParameterComponent().setType(outputComponent.getType())
				.setValue(outputComponent.getValue().copy());
		inputComponent.setExtension(outputComponent.getExtension());

		return inputComponent;
	}
}
