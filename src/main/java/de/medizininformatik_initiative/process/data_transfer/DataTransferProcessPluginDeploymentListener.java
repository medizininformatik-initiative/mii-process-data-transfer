package de.medizininformatik_initiative.process.data_transfer;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.authorization.AuthorizationProvider;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.ProcessPluginDeploymentListener;

public class DataTransferProcessPluginDeploymentListener implements ProcessPluginDeploymentListener, InitializingBean
{
	private final ProcessPluginApi api;

	private final String fhirStoreIdDic;
	private final String fhirStoreIdDms;

	private final KeyProvider keyProvider;
	private final AuthorizationProvider authorizationProvider;

	public DataTransferProcessPluginDeploymentListener(ProcessPluginApi api, String fhirStoreIdDic,
			String fhirStoreIdDms, KeyProvider keyProvider, AuthorizationProvider authorizationProvider)
	{
		this.api = api;
		this.fhirStoreIdDic = fhirStoreIdDic;
		this.fhirStoreIdDms = fhirStoreIdDms;
		this.keyProvider = keyProvider;
		this.authorizationProvider = authorizationProvider;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(authorizationProvider, "authorizationProvider");
	}


	@Override
	public void onProcessesDeployed(List<String> activeProcesses)
	{
		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND))
		{
			authorizationProvider.searchCheckAddAndUpdateAuthorizationDataSend();

			testConnection(fhirStoreIdDic);
		}

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE))
		{
			authorizationProvider.searchCheckAddAndUpdateAuthorizationDataReceive();

			testConnection(fhirStoreIdDms);

			Objects.requireNonNull(keyProvider.getPublicKey(), "PublicKey");
			Objects.requireNonNull(keyProvider.getPrivateKey(), "PrivateKey");
			keyProvider.createPublicKeyIfNotExists();
		}
	}

	private void testConnection(String fhirStoreId)
	{
		CapabilityStatement conformance = api.getDsfClientProvider().getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("DSF FHIR client '" + fhirStoreId + "' not configured"))
				.getConformance();

		Objects.requireNonNull(conformance, "Connection test for DSF FHIR client '" + fhirStoreId + "' failed"
				+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + "CapabilityStatement is null");
	}
}
