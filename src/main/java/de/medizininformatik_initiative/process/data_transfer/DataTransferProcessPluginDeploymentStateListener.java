package de.medizininformatik_initiative.process.data_transfer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.MetadataResource;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.util.MetadataResourceConverter;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.ProcessPluginDeploymentStateListener;

public class DataTransferProcessPluginDeploymentStateListener
		implements ProcessPluginDeploymentStateListener, InitializingBean
{
	private final ProcessPluginApi api;

	private final FhirClientFactory dicFhirClientFactory;
	private final FhirClientFactory dmsFhirClientFactory;

	private final KeyProvider keyProvider;

	private final MetadataResourceConverter metadataResourceConverter;

	public DataTransferProcessPluginDeploymentStateListener(ProcessPluginApi api,
			FhirClientFactory dicFhirClientFactory, FhirClientFactory dmsFhirClientConfig, KeyProvider keyProvider,
			MetadataResourceConverter metadataResourceConverter)
	{
		this.api = api;
		this.dicFhirClientFactory = dicFhirClientFactory;
		this.dmsFhirClientFactory = dmsFhirClientConfig;
		this.keyProvider = keyProvider;
		this.metadataResourceConverter = metadataResourceConverter;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(dicFhirClientFactory, "dicFhirClientFactory");
		Objects.requireNonNull(dmsFhirClientFactory, "dmsFhirClientFactory");
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(metadataResourceConverter, "metadataResourceConverter");
	}

	@Override
	public void onProcessesDeployed(List<String> activeProcesses)
	{
		// TODO: function metadataResourceConverter.searchAndConvertOlderResourcesIfCurrentIsNewestResource
		// added because CodeSystems with different versions cannot be used in DSF API 1.x.
		// Remove for DSF API 2.x API where CodeSystem versioning is fixed.

		metadataResourceConverter.searchAndConvertOlderResourcesIfCurrentIsNewestResource(
				ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER, CodeSystem.class,
				this::filterCodeSystemsWithNonMatchingConceptCodes, this::adaptCodeSystemsReplacingConcepts);

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND))
			dicFhirClientFactory.testConnection();

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE))
		{
			dmsFhirClientFactory.testConnection();
			keyProvider.createPublicKeyIfNotExists();
		}
	}

	private void adaptCodeSystemsReplacingConcepts(CodeSystem currentResource, CodeSystem olderResource)
	{
		olderResource.setConcept(currentResource.getConcept());
		updateResource(olderResource);
	}

	private boolean filterCodeSystemsWithNonMatchingConceptCodes(CodeSystem currentCodeSystem,
			CodeSystem olderCodeSystem)
	{
		return !getConceptCodes(currentCodeSystem).equals(getConceptCodes(olderCodeSystem));
	}

	private Set<String> getConceptCodes(CodeSystem codeSystem)
	{
		return codeSystem.getConcept().stream().map(CodeSystem.ConceptDefinitionComponent::getCode)
				.collect(Collectors.toSet());
	}

	private void updateResource(MetadataResource resource)
	{
		api.getFhirWebserviceClientProvider().getLocalWebserviceClient().update(resource);
	}
}
