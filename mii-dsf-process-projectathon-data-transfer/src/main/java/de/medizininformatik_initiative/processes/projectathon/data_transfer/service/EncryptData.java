package de.medizininformatik_initiative.processes.projectathon.data_transfer.service;

import static java.util.stream.Collectors.toList;

import static de.medizininformatik_initiative.processes.projectathon.data_transfer.ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_COORDINATING_SITE_IDENTIFIER;
import static de.medizininformatik_initiative.processes.projectathon.data_transfer.ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET;
import static de.medizininformatik_initiative.processes.projectathon.data_transfer.ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED;
import static de.medizininformatik_initiative.processes.projectathon.data_transfer.ConstantsDataTransfer.CODESYSTEM_MII_CRYPTOGRAPHY;
import static de.medizininformatik_initiative.processes.projectathon.data_transfer.ConstantsDataTransfer.CODESYSTEM_MII_CRYPTOGRAPHY_VALUE_PUBLIC_KEY;
import static org.highmed.dsf.bpe.ConstantsBase.CODESYSTEM_HIGHMED_ORGANIZATION_TYPE;
import static org.highmed.dsf.bpe.ConstantsBase.CODESYSTEM_HIGHMED_ORGANIZATION_TYPE_VALUE_COS;
import static org.highmed.dsf.bpe.ConstantsBase.NAMINGSYSTEM_HIGHMED_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.codec.digest.DigestUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.highmed.dsf.bpe.delegate.AbstractServiceDelegate;
import org.highmed.dsf.fhir.authorization.read.ReadAccessHelper;
import org.highmed.dsf.fhir.client.FhirWebserviceClientProvider;
import org.highmed.dsf.fhir.organization.EndpointProvider;
import org.highmed.dsf.fhir.task.TaskHelper;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.processes.projectathon.data_transfer.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.projectathon.data_transfer.crypto.RsaAesGcmUtil;

public class EncryptData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(EncryptData.class);

	private final EndpointProvider endpointProvider;

	public EncryptData(FhirWebserviceClientProvider clientProvider, TaskHelper taskHelper,
			ReadAccessHelper readAccessHelper, EndpointProvider endpointProvider)
	{
		super(clientProvider, taskHelper, readAccessHelper);

		this.endpointProvider = endpointProvider;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(endpointProvider, "endpointProvider");
	}

	@Override
	protected void doExecute(DelegateExecution execution)
	{
		String coordinatingSiteIdentifier = (String) execution
				.getVariable(BPMN_EXECUTION_VARIABLE_COORDINATING_SITE_IDENTIFIER);
		Bundle toEncrypt = (Bundle) execution.getVariable(BPMN_EXECUTION_VARIABLE_DATA_SET);

		PublicKey publicKey = readPublicKey(coordinatingSiteIdentifier);
		byte[] encrypted = encrypt(publicKey, toEncrypt);

		execution.setVariable(BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED, encrypted);
	}

	private PublicKey readPublicKey(String coordinatingSiteIdentifier)
	{
		String url = getEndpointUrl(coordinatingSiteIdentifier);
		Bundle searchBundle = getFhirWebserviceClientProvider().getWebserviceClient(url).search(Bundle.class,
				Map.of("identifier", Collections.singletonList(
						CODESYSTEM_MII_CRYPTOGRAPHY + "|" + CODESYSTEM_MII_CRYPTOGRAPHY_VALUE_PUBLIC_KEY)));

		if (searchBundle.getTotal() != 1 && searchBundle.getEntryFirstRep().getResource() instanceof Bundle)
			throw new IllegalStateException("Could not find PublicKey Bundle of organization with identifier='"
					+ coordinatingSiteIdentifier + "'");

		Bundle publicKeyBundle = (Bundle) searchBundle.getEntryFirstRep().getResource();

		logger.info("Downloaded PublicKey Bundle with id='{}' from organization with identifier='{}'",
				publicKeyBundle.getId(), coordinatingSiteIdentifier);

		DocumentReference documentReference = getDocumentReference(publicKeyBundle);
		Binary binary = getBinary(publicKeyBundle);

		PublicKey publicKey = getPublicKey(binary, publicKeyBundle.getId());

		checkHash(documentReference, publicKey, publicKeyBundle.getId());

		return publicKey;
	}

	private String getEndpointUrl(String identifier)
	{
		return endpointProvider.getFirstConsortiumEndpointAdress(
				NAMINGSYSTEM_HIGHMED_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM,
				CODESYSTEM_HIGHMED_ORGANIZATION_TYPE, CODESYSTEM_HIGHMED_ORGANIZATION_TYPE_VALUE_COS, identifier).get();
	}

	private DocumentReference getDocumentReference(Bundle bundle)
	{
		List<DocumentReference> documentReferences = bundle.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof DocumentReference)
				.map(r -> ((DocumentReference) r)).collect(toList());

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException(
					"Could not find any DocumentReference in PublicKey Bundle with id='" + bundle.getId() + "'");

		if (documentReferences.size() > 1)
			logger.warn("Found > 1 DocumentReferences ({}) in PublicKey Bundle with id='{}'", documentReferences.size(),
					bundle.getId());

		return documentReferences.get(0);
	}

	private Binary getBinary(Bundle bundle)
	{
		List<Binary> binaries = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof Binary).map(b -> ((Binary) b)).collect(toList());

		if (binaries.size() < 1)
			throw new IllegalArgumentException(
					"Could not find any Binary in PublicKey Bundle with id='" + bundle.getId() + "'");

		if (binaries.size() > 1)
			logger.warn("Found > 1 Binaries ({}) in PublicKey Bundle with id='{}'", binaries.size(), bundle.getId());

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
			logger.info("Could not generate PublicKey from Binary in PublicKey Bundle with id='{}'", publicKeyBundleId);
			throw new RuntimeException(
					"Could not generate PublicKey from Binary in PublicKey Bundle with id='" + publicKeyBundleId + "'",
					exception);
		}
	}

	private void checkHash(DocumentReference documentReference, PublicKey publicKey, String bundleId)
	{
		long numberOfHashes = documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasHash)
				.map(Attachment::getHash).count();

		if (numberOfHashes < 1)
		{
			throw new RuntimeException(
					"Could not find any sha256-hash in DocumentReference from Bundle with id='" + bundleId + "'");
		}

		if (numberOfHashes > 1)
		{
			logger.warn("DocumentReference contains > 1 sha256-hashes ({}), using the first from Bundle with id='{}'",
					numberOfHashes, bundleId);
		}

		byte[] documentReferenceHash = documentReference.getContentFirstRep().getAttachment().getHash();
		byte[] publicKeyHash = DigestUtils.sha256(publicKey.getEncoded());

		logger.debug("DocumentReference PublicKey sha256-hash='{}' from Bundle with id='{}'",
				byteToHex(documentReferenceHash), bundleId);
		logger.debug("PublicKey actual sha256-hash='{}' from Bundle with id='{}'", byteToHex(publicKeyHash), bundleId);

		if (!Arrays.equals(documentReferenceHash, publicKeyHash))
		{
			throw new RuntimeException(
					"Sha256-hash in DocumentReference does not match computed sha256-hash of Binary in Bundle with id='"
							+ bundleId + "'");
		}
	}

	private String byteToHex(byte[] toConvert)
	{
		StringBuilder hexString = new StringBuilder(2 * toConvert.length);

		for (byte b : toConvert)
		{
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1)
			{
				hexString.append('0');
			}
			hexString.append(hex);
		}

		return hexString.toString();
	}

	private byte[] encrypt(PublicKey publicKey, Bundle bundle)
	{
		try
		{
			byte[] toEncrypt = FhirContext.forR4().newXmlParser().encodeResourceToString(bundle)
					.getBytes(StandardCharsets.UTF_8);

			return RsaAesGcmUtil.encrypt(publicKey, toEncrypt);
		}
		catch (Exception exception)
		{
			String taskId = getLeadingTaskFromExecutionVariables().getId();
			throw new RuntimeException("Could not encrypt data-set to transmit for task with id='" + taskId + "'");
		}
	}
}