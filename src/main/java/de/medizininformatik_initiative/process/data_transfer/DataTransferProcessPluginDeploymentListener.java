package de.medizininformatik_initiative.process.data_transfer;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.authorization.AuthorizationProvider;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.util.MetadataResourceConverter;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.ProcessPluginDeploymentListener;

public class DataTransferProcessPluginDeploymentListener implements ProcessPluginDeploymentListener, InitializingBean
{
	private final ProcessPluginApi api;

	private final String fhirStoreIdDic;
	private final String fhirStoreIdDms;

	private final KeyProvider keyProvider;

	private final MetadataResourceConverter metadataResourceConverter;
	private final AuthorizationProvider authorizationProvider;

	public DataTransferProcessPluginDeploymentListener(ProcessPluginApi api, String fhirStoreIdDic,
			String fhirStoreIdDms, KeyProvider keyProvider, MetadataResourceConverter metadataResourceConverter,
			AuthorizationProvider authorizationProvider)
	{
		this.api = api;
		this.fhirStoreIdDic = fhirStoreIdDic;
		this.fhirStoreIdDms = fhirStoreIdDms;
		this.keyProvider = keyProvider;
		this.metadataResourceConverter = metadataResourceConverter;
		this.authorizationProvider = authorizationProvider;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(metadataResourceConverter, "metadataResourceConverter");
		Objects.requireNonNull(authorizationProvider, "authorizationProvider");
	}


	@Override
	public void onProcessesDeployed(List<String> activeProcesses)
	{
		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND))
		{
			authorizationProvider.searchCheckAddAndUpdateAuthorizationDataSend();

			CapabilityStatement conformance = api.getDsfClientProvider().getById(fhirStoreIdDic)
					.orElseThrow(() -> new RuntimeException("DSF FHIR client '" + fhirStoreIdDic + "' not configured"))
					.getConformance();

			Objects.requireNonNull(conformance, "Connection test for DSF FHIR client '" + fhirStoreIdDic
					+ "' failed - CapabilityStatement is null");
		}

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE))
		{
			authorizationProvider.searchCheckAddAndUpdateAuthorizationDataReceive();

			CapabilityStatement conformance = api.getDsfClientProvider().getById(fhirStoreIdDms)
					.orElseThrow(() -> new RuntimeException("DSF FHIR client '" + fhirStoreIdDms + "' not configured"))
					.getConformance();

			Objects.requireNonNull(conformance, "Connection test for DSF FHIR client '" + fhirStoreIdDms
					+ "' failed - CapabilityStatement is null");

			Objects.requireNonNull(keyProvider.getPublicKey(), "PublicKey");
			Objects.requireNonNull(keyProvider.getPrivateKey(), "PrivateKey");

			keyProvider.createPublicKeyIfNotExists();
		}
	}
}
