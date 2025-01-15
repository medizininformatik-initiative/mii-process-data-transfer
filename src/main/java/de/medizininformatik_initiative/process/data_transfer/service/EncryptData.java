package de.medizininformatik_initiative.process.data_transfer.service;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.crypto.RsaAesGcmUtil;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.constants.NamingSystems;
import dev.dsf.bpe.v1.variables.Variables;

public class EncryptData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(EncryptData.class);

	private final KeyProvider keyProvider;

	public EncryptData(ProcessPluginApi api, KeyProvider keyProvider)
	{
		super(api);
		this.keyProvider = keyProvider;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(keyProvider, "keyProvider");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		List<Resource> resources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);

		logger.info("Encrypting data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			PublicKey publicKey = readPublicKey(dmsIdentifier);
			String localOrganizationIdentifier = getLocalOrganizationIdentifier();

			List<Binary> encryptedResources = encryptResources(resources, publicKey, localOrganizationIdentifier,
					dmsIdentifier);
			variables.setResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES, encryptedResources);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not encrypt data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());

			String error = "Encrypt data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private PublicKey readPublicKey(String dmsIdentifier)
	{
		String url = getEndpointUrl(dmsIdentifier);
		Optional<Bundle> publicKeyBundleOptional = keyProvider.readPublicKeyIfExists(url);

		if (publicKeyBundleOptional.isEmpty())
			throw new IllegalStateException(
					"Could not find PublicKey Bundle of organization with identifier'" + dmsIdentifier + "'");

		logger.debug("Downloaded PublicKey Bundle from organization with identifier '{}'", dmsIdentifier);

		Bundle publicKeyBundle = publicKeyBundleOptional.get();
		DocumentReference documentReference = getDocumentReference(publicKeyBundle);
		Binary binary = getBinary(publicKeyBundle);

		PublicKey publicKey = getPublicKey(binary, publicKeyBundle.getId());
		checkHash(documentReference, publicKey);

		return publicKey;
	}

	private String getEndpointUrl(String identifier)
	{
		return api.getEndpointProvider().getEndpointAddress(NamingSystems.OrganizationIdentifier.withValue(
				ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM),
				NamingSystems.OrganizationIdentifier.withValue(identifier),
				new Coding().setSystem(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE)
						.setCode(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE_VALUE_DMS))
				.orElseThrow(() -> new RuntimeException(
						"Could not find Endpoint for organization with identifier '" + identifier + "'"));
	}

	private DocumentReference getDocumentReference(Bundle bundle)
	{
		List<DocumentReference> documentReferences = bundle.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof DocumentReference)
				.map(r -> (DocumentReference) r).toList();

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException("Could not find any DocumentReference in PublicKey Bundle");

		if (documentReferences.size() > 1)
			logger.warn("Found {} DocumentReferences in PublicKey Bundle, using the first", documentReferences.size());

		return documentReferences.get(0);
	}

	private Binary getBinary(Bundle bundle)
	{
		List<Binary> binaries = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof Binary).map(b -> (Binary) b).toList();

		if (binaries.size() < 1)
			throw new IllegalArgumentException("Could not find any Binary in PublicKey Bundle");

		if (binaries.size() > 1)
			logger.warn("Found {} Binaries in PublicKey Bundle, using the first", binaries.size());

		return binaries.get(0);
	}

	private PublicKey getPublicKey(Binary binary, String publicKeyBundleId)
	{
		try
		{
			return KeyProvider.fromBytes(binary.getContent());
		}
		catch (Exception exception)
		{
			logger.warn("Could not read PublicKey from Binary in PublicKey Bundle with id '{}' - {}", publicKeyBundleId,
					exception.getMessage());
			throw new RuntimeException("Could not read PublicKey from Binary in PublicKey Bundle with id '"
					+ publicKeyBundleId + "' - " + exception.getMessage(), exception);
		}
	}

	private void checkHash(DocumentReference documentReference, PublicKey publicKey)
	{
		long numberOfHashes = documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasHash)
				.map(Attachment::getHash).count();

		if (numberOfHashes < 1)
			throw new RuntimeException("Could not find any sha256-hash in DocumentReference");

		if (numberOfHashes > 1)
			logger.warn("DocumentReference contains {} sha256-hashes, using the first", numberOfHashes);

		byte[] documentReferenceHash = documentReference.getContentFirstRep().getAttachment().getHash();
		byte[] publicKeyHash = DigestUtils.sha256(publicKey.getEncoded());

		logger.debug("DocumentReference PublicKey sha256-hash '{}'", Hex.encodeHexString(documentReferenceHash));
		logger.debug("PublicKey actual sha256-hash '{}'", Hex.encodeHexString(publicKeyHash));

		if (!Arrays.equals(documentReferenceHash, publicKeyHash))
			throw new RuntimeException(
					"Sha256-hash in DocumentReference does not match computed sha256-hash of Binary");
	}

	private String getLocalOrganizationIdentifier()
	{
		return api.getOrganizationProvider().getLocalOrganizationIdentifierValue()
				.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifierValue is null"));
	}

	private List<Binary> encryptResources(List<Resource> resources, PublicKey publicKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		return resources.stream()
				.map(r -> encryptResource(r, publicKey, sendingOrganizationIdentifier, receivingOrganizationIdentifier))
				.toList();
	}

	private Binary encryptResource(Resource resource, PublicKey publicKey, String sendingOrganizationIdentifier,
			String receivingOrganizationIdentifier)
	{
		try
		{
			byte[] toEncrypt = getBytesToEncrypt(resource);
			byte[] encrypted = RsaAesGcmUtil.encrypt(publicKey, toEncrypt, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);

			String mimeType = getMimeType(resource);
			return new Binary().setData(encrypted).setContentType(mimeType);
		}
		catch (Exception exception)
		{
			logger.warn("Could not encrypt data-set to transmit - {}", exception.getMessage());
			throw new RuntimeException("Could not encrypt data-set to transmit - " + exception.getMessage());
		}
	}

	private byte[] getBytesToEncrypt(Resource resource)
	{
		if (resource instanceof Binary binary)
			return binary.getData();
		else
			return api.getFhirContext().newJsonParser().encodeResourceToString(resource)
					.getBytes(StandardCharsets.UTF_8);
	}

	private String getMimeType(Resource resource)
	{
		if (resource instanceof Binary binary)
			return binary.getContentType();
		else
			return "application/fhir+json";
	}
}
