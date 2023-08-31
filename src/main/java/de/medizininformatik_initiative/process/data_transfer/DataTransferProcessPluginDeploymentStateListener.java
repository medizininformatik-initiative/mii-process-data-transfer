package de.medizininformatik_initiative.process.data_transfer;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import dev.dsf.bpe.v1.ProcessPluginDeploymentStateListener;

public class DataTransferProcessPluginDeploymentStateListener
		implements ProcessPluginDeploymentStateListener, InitializingBean
{
	private final FhirClientFactory dicFhirClientFactory;
	private final FhirClientFactory dmsFhirClientFactory;

	private final KeyProvider keyProvider;

	public DataTransferProcessPluginDeploymentStateListener(FhirClientFactory dicFhirClientFactory,
			FhirClientFactory dmsFhirClientConfig, KeyProvider keyProvider)
	{
		this.dicFhirClientFactory = dicFhirClientFactory;
		this.dmsFhirClientFactory = dmsFhirClientConfig;
		this.keyProvider = keyProvider;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(dicFhirClientFactory, "dicFhirClientFactory");
		Objects.requireNonNull(dmsFhirClientFactory, "dmsFhirClientFactory");
		Objects.requireNonNull(keyProvider, "keyProvider");
	}

	@Override
	public void onProcessesDeployed(List<String> activeProcesses)
	{
		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND))
			dicFhirClientFactory.testConnection();

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE))
		{
			dmsFhirClientFactory.testConnection();
			keyProvider.createPublicKeyIfNotExists();
		}
	}
}
