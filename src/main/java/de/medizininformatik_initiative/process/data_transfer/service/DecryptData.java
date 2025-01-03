package de.medizininformatik_initiative.process.data_transfer.service;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.crypto.RsaAesGcmUtil;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class DecryptData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DecryptData.class);

	private final KeyProvider keyProvider;
	private final DataSetStatusGenerator statusGenerator;

	public DecryptData(ProcessPluginApi api, KeyProvider keyProvider, DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.keyProvider = keyProvider;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		List<Binary> encryptedResources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);
		String localOrganizationIdentifier = getLocalOrganizationIdentifier();
		String sendingOrganizationIdentifier = getSendingOrganizationIdentifier(variables);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		logger.info(
				"Decrypting data-set from organization '{}' with project-identifier '{}' referenced in Task with id '{}'",
				sendingOrganizationIdentifier, projectIdentifier, task.getId());

		try
		{
			List<Resource> decryptedResources = decryptResources(variables, keyProvider.getPrivateKey(),
					encryptedResources, sendingOrganizationIdentifier, localOrganizationIdentifier);

			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, decryptedResources);
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Decrypt data-set failed"));
			variables.updateTask(task);

			logger.warn(
					"Could not decrypt data-set from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());

			String error = "Decrypt data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private String getLocalOrganizationIdentifier()
	{
		return api.getOrganizationProvider().getLocalOrganizationIdentifierValue()
				.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifierValue is null"));
	}

	private String getSendingOrganizationIdentifier(Variables variables)
	{
		return variables.getStartTask().getRequester().getIdentifier().getValue();
	}

	private List<Resource> decryptResources(Variables variables, PrivateKey privateKey, List<Binary> binaries,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		return binaries.stream().map(b -> decryptResource(variables, privateKey, b, sendingOrganizationIdentifier,
				receivingOrganizationIdentifier)).toList();
	}

	private Resource decryptResource(Variables variables, PrivateKey privateKey, Binary binary,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		try
		{
			byte[] decrypted = RsaAesGcmUtil.decrypt(privateKey, binary.getContent(), sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);
			String resourceString = new String(decrypted, StandardCharsets.UTF_8);

			return (Resource) FhirContext.forR4().newXmlParser().parseResource(resourceString);
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
