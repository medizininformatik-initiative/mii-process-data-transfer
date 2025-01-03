package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.mimetype.MimeTypeHelper;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ValidateDataDms extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ValidateDataDms.class);

	private final MimeTypeHelper mimeTypeHelper;
	private final DataSetStatusGenerator statusGenerator;

	public ValidateDataDms(ProcessPluginApi api, MimeTypeHelper mimeTypeHelper, DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.mimeTypeHelper = mimeTypeHelper;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(mimeTypeHelper, "mimeTypeHelper");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String sendingOrganization = task.getRequester().getIdentifier().getValue();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		List<Resource> resources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);

		logger.info(
				"Validating data-set from organization '{}' and project-identifier '{}' referenced in Task with id '{}'",
				sendingOrganization, projectIdentifier, task.getId());

		try
		{
			resources.forEach(this::validate);
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Validate data-set failed"));
			variables.updateTask(task);

			logger.warn(
					"Could not validate data-set from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());

			String error = "Validate data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private void validate(Resource resource)
	{
		String mimeType = mimeTypeHelper.getMimeType(resource);
		byte[] data = mimeTypeHelper.getData(resource);
		mimeTypeHelper.validate(data, mimeType);
	}
}
