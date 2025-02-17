package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.mimetype.MimeTypeHelper;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ValidateDataDic extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ValidateDataDic.class);

	private final MimeTypeHelper mimeTypeHelper;
	private final FhirClientFactory fhirClientFactory;

	public ValidateDataDic(ProcessPluginApi api, MimeTypeHelper mimeTypeHelper, FhirClientFactory fhirClientFactory)
	{
		super(api);
		this.mimeTypeHelper = mimeTypeHelper;
		this.fhirClientFactory = fhirClientFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(mimeTypeHelper, "mimeTypeHelper");
		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		logger.info("Validating data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, variables.getStartTask().getId());

		try
		{
			List<Resource> resources = variables
					.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);
			resources.forEach(this::validate);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not validate data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());

			String error = "Validating data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private void validate(Resource resource)
	{
		if (resource instanceof ListResource list)
			validateStream(list);
		else
			validateResource(resource);
	}

	private void validateResource(Resource resource)
	{
		String mimeType = mimeTypeHelper.getMimeType(resource);
		byte[] data = mimeTypeHelper.getData(resource);

		mimeTypeHelper.validate(data, mimeType);
	}

	private void validateStream(ListResource list)
	{
		list.getEntry().stream().filter(e -> e.hasItem())
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.forEach(e -> doValidateStream(e));
	}

	private void doValidateStream(ListResource.ListEntryComponent listEntry)
	{
		IdType url = (IdType) listEntry.getItem().getReferenceElement();
		String mimetype = listEntry.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);

		InputStream stream = fhirClientFactory.getBinaryStreamFhirClient().read(url, mimetype);
		mimeTypeHelper.validate(stream, mimetype);
	}
}
