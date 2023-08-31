package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.Objects;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.MimeTypeHelper;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ValidateDataDic extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ValidateDataDic.class);

	private final MimeTypeHelper mimeTypeHelper;

	public ValidateDataDic(ProcessPluginApi api, MimeTypeHelper mimeTypeHelper)
	{
		super(api);
		this.mimeTypeHelper = mimeTypeHelper;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(mimeTypeHelper, "mimeTypeHelper");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		Resource resource = variables.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCE);

		logger.info("Validating data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, variables.getStartTask().getId());

		String mimeType = mimeTypeHelper.getMimeType(resource);
		byte[] data = mimeTypeHelper.getData(resource);

		mimeTypeHelper.validate(data, mimeType);
	}
}
