package de.medizininformatik_initiative.process.data_transfer.service;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;
import dev.dsf.fhir.client.BasicFhirWebserviceClient;

public class DeleteData extends AbstractServiceDelegate
{
	private static final Logger logger = LoggerFactory.getLogger(DeleteData.class);

	public DeleteData(ProcessPluginApi api)
	{
		super(api);
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		IdType binaryId = new IdType(
				variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE));

		logger.info(
				"Permanently deleting encrypted Binary with id '{}' provided for DMS '{}' and project-identifier '{}' "
						+ "referenced in Task with id '{}'",
				binaryId.getValue(), dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			deletePermanently(binaryId);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not permanently delete data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());
			throw new RuntimeException("Could not permanently delete data-set for DMS '" + dmsIdentifier
					+ "' and project-identifier '" + projectIdentifier + "' referenced in Task with id '" + task.getId()
					+ "' - " + exception.getMessage(), exception);
		}
	}

	private void deletePermanently(IdType binaryId)
	{
		BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);
		client.delete(Binary.class, binaryId.getIdPart());
		client.deletePermanently(Binary.class, binaryId.getIdPart());
	}
}
