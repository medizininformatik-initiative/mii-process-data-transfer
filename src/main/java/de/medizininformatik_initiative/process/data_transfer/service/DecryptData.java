package de.medizininformatik_initiative.process.data_transfer.service;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.util.DataSetStatusGenerator;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.crypto.RsaAesGcmUtil;
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class DecryptData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DecryptData.class);

	private final KeyProvider keyProvider;
	private final DataLogger dataLogger;
	private final DataSetStatusGenerator statusGenerator;

	public DecryptData(ProcessPluginApi api, KeyProvider keyProvider, DataLogger dataLogger,
			DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.keyProvider = keyProvider;
		this.dataLogger = dataLogger;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(dataLogger, "dataLogger");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		byte[] bundleEncrypted = variables
				.getByteArray(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED);
		String localOrganizationIdentifier = api.getOrganizationProvider().getLocalOrganizationIdentifierValue()
				.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifierValue is null"));
		String sendingOrganizationIdentifier = getSendingOrganizationIdentifier(variables);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		logger.info("Decrypting data-set from organization '{}' with project-identifier '{}' in Task with id '{}'",
				sendingOrganizationIdentifier, projectIdentifier, task.getId());

		try
		{
			Bundle bundleDecrypted = decryptBundle(variables, keyProvider.getPrivateKey(), bundleEncrypted,
					sendingOrganizationIdentifier, localOrganizationIdentifier);

			dataLogger.logResource("Decrypted Transfer Bundle", bundleDecrypted);

			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET, bundleDecrypted);
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsDataTransfer.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR, "Decrypt data-set failed"));
			variables.updateTask(task);

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE,
					"Decrypt data-set failed");

			logger.warn(
					"Could not decrypt data-set with id '{}' from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE),
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR,
					"Decrypt data-set - " + exception.getMessage());
		}
	}

	private String getSendingOrganizationIdentifier(Variables variables)
	{
		return variables.getStartTask().getRequester().getIdentifier().getValue();
	}

	private Bundle decryptBundle(Variables variables, PrivateKey privateKey, byte[] bundleEncrypted,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		try
		{
			byte[] bundleDecrypted = RsaAesGcmUtil.decrypt(privateKey, bundleEncrypted, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);
			String bundleString = new String(bundleDecrypted, StandardCharsets.UTF_8);
			return (Bundle) FhirContext.forR4().newXmlParser().parseResource(bundleString);
		}
		catch (Exception exception)
		{
			String taskId = variables.getStartTask().getId();
			logger.warn("Could not decrypt received data-set for Task with id '{}' - {}", taskId,
					exception.getMessage());
			throw new RuntimeException("Could not decrypt received data-set for Task with id '" + taskId + "'",
					exception);
		}
	}
}
