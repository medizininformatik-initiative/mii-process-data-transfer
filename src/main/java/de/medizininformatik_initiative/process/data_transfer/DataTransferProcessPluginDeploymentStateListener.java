package de.medizininformatik_initiative.process.data_transfer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeSystem;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.ProcessPluginDeploymentStateListener;

public class DataTransferProcessPluginDeploymentStateListener
		implements ProcessPluginDeploymentStateListener, InitializingBean
{
	private final ProcessPluginApi api;

	private final FhirClientFactory dicFhirClientFactory;
	private final FhirClientFactory dmsFhirClientFactory;

	private final KeyProvider keyProvider;

	private final String resourcesVersion;

	private record MinorMajorVersion(int major, int minor)
	{
	}

	public DataTransferProcessPluginDeploymentStateListener(ProcessPluginApi api,
			FhirClientFactory dicFhirClientFactory, FhirClientFactory dmsFhirClientConfig, KeyProvider keyProvider,
			String resourcesVersion)
	{
		this.api = api;
		this.dicFhirClientFactory = dicFhirClientFactory;
		this.dmsFhirClientFactory = dmsFhirClientConfig;
		this.keyProvider = keyProvider;
		this.resourcesVersion = resourcesVersion;
	}

	@Override
	public void afterPropertiesSet()
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(dicFhirClientFactory, "dicFhirClientFactory");
		Objects.requireNonNull(dmsFhirClientFactory, "dmsFhirClientFactory");
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(resourcesVersion, "resourcesVersion");
	}

	@Override
	public void onProcessesDeployed(List<String> activeProcesses)
	{
		// TODO: functions updateOlderCodeSystemsIfCurrentIsNewestCodeSystem
		// added because CodeSystems with different versions cannot be used in DSF API 1.x.
		// Remove for DSF API 2.x API where CodeSystem versioning is fixed.

		updateOlderCodeSystemsIfCurrentIsNewestCodeSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER);

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND))
			dicFhirClientFactory.testConnection();

		if (activeProcesses.contains(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE))
		{
			dmsFhirClientFactory.testConnection();
			keyProvider.createPublicKeyIfNotExists();
		}
	}

	private void updateOlderCodeSystemsIfCurrentIsNewestCodeSystem(String url)
	{
		Bundle searchResult = searchCodeSystem(url);
		List<CodeSystem> allCodeSystems = extractCodeSystems(searchResult, url);

		CodeSystem currentCodeSystem = filterCurrentCodeSystem(allCodeSystems, url);
		List<CodeSystem> olderNewerCodeSystems = filterOlderNewerCodeSystems(allCodeSystems);

		if (currentIsNewestCodeSystem(olderNewerCodeSystems))
		{
			List<CodeSystem> codeSystemsWithNonMatchingConceptCodes = filterCodeSystemsWithNonMatchingConceptCodesAndAdaptToCurrentCodeSystemConceptCodes(
					currentCodeSystem, olderNewerCodeSystems);
			updateCodeSystemsWithNonMatchingConceptCodes(codeSystemsWithNonMatchingConceptCodes);
		}
	}

	private Bundle searchCodeSystem(String url)
	{
		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient().search(CodeSystem.class,
				Map.of("url", List.of(url)));
	}

	private List<CodeSystem> extractCodeSystems(Bundle bundle, String codeSystemUrl)
	{
		return bundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof CodeSystem)
				.map(r -> (CodeSystem) r).filter(c -> codeSystemUrl.equals(c.getUrl())).toList();
	}

	private CodeSystem filterCurrentCodeSystem(List<CodeSystem> all, String codeSystemUrl)
	{
		return all.stream().filter(c -> resourcesVersion.equals(c.getVersion())).findFirst()
				.orElseThrow(() -> new RuntimeException("CodeSystem " + codeSystemUrl + "|" + resourcesVersion));
	}

	private List<CodeSystem> filterOlderNewerCodeSystems(List<CodeSystem> all)
	{
		return all.stream().filter(c -> !resourcesVersion.equals(c.getVersion())).toList();
	}

	private boolean currentIsNewestCodeSystem(List<CodeSystem> olderNewerCodeSystems)
	{
		return olderNewerCodeSystems.stream().noneMatch(this::isNewerCodeSystem);
	}

	private boolean isNewerCodeSystem(CodeSystem codeSystem)
	{
		MinorMajorVersion current = getMajorMinorVersion(resourcesVersion);
		MinorMajorVersion olderNewer = getMajorMinorVersion(codeSystem.getVersion());

		return current.major <= olderNewer.major && current.minor < olderNewer.minor;
	}

	private MinorMajorVersion getMajorMinorVersion(String version)
	{
		if (version.matches("\\d\\.\\d"))
		{
			String[] minorMajor = version.split("\\.");
			return new MinorMajorVersion(Integer.parseInt(minorMajor[0]), Integer.parseInt(minorMajor[1]));
		}

		throw new RuntimeException("Fhir resource version " + version + " does not match regex \\d\\.\\d");
	}

	private List<CodeSystem> filterCodeSystemsWithNonMatchingConceptCodesAndAdaptToCurrentCodeSystemConceptCodes(
			CodeSystem currentCodeSystem, List<CodeSystem> olderCodeSystems)
	{
		Set<String> currentConceptCodes = getConceptCodes(currentCodeSystem);
		return olderCodeSystems.stream().filter(c -> !currentConceptCodes.equals(getConceptCodes(c)))
				.map(c -> c.setConcept(currentCodeSystem.getConcept())).toList();
	}

	private Set<String> getConceptCodes(CodeSystem codeSystem)
	{
		return codeSystem.getConcept().stream().map(CodeSystem.ConceptDefinitionComponent::getCode)
				.collect(Collectors.toSet());
	}

	private void updateCodeSystemsWithNonMatchingConceptCodes(List<CodeSystem> codeSystems)
	{
		codeSystems.forEach(c -> api.getFhirWebserviceClientProvider().getLocalWebserviceClient().update(c));
	}
}
