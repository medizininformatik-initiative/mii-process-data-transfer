package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.PRELIMINARY;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import de.medizininformatik_initiative.processes.common.util.MimeTypeHelper;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.constants.CodeSystems;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.variables.Target;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class EncryptAndStoreData implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(EncryptAndStoreData.class);

	private final String fhirStoreId;
	private final boolean fhirBinaryStreamReadUseHapiBlobStorageOperation;
	private final DataSetStatusGenerator statusGenerator;
	private final KeyProvider keyProvider;
	private final boolean dicEmailEnabled;

	public EncryptAndStoreData(String fhirStoreId, boolean fhirBinaryStreamReadUseHapiBlobStorageOperation,
			DataSetStatusGenerator statusGenerator, KeyProvider keyProvider, boolean dicEmailEnabled)
	{
		this.fhirStoreId = fhirStoreId;
		this.fhirBinaryStreamReadUseHapiBlobStorageOperation = fhirBinaryStreamReadUseHapiBlobStorageOperation;
		this.statusGenerator = statusGenerator;
		this.keyProvider = keyProvider;
		this.dicEmailEnabled = dicEmailEnabled;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String consortiumIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		DocumentReference initialDocumentReference = variables
				.getFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE);
		List<Resource> resources = variables
				.getFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DATA_RESOURCES);

		logger.info("Encrypting and storing data-set for DMS '{}' and project-identifier '{}' referenced in Task '{}'",
				dmsIdentifier, projectIdentifier, api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

		ListResource transferBinaryReferenceList = new ListResource();
		PublicKey publicKey = readPublicKey(api, consortiumIdentifier, dmsIdentifier, projectIdentifier, task);

		try
		{
			DocumentReference transferDocumentReference = createAndStoreDocumentReference(api, projectIdentifier,
					initialDocumentReference, dmsIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
					getDsfFhirServerAbsoluteId(api, transferDocumentReference.getIdElement()));

			encryptAndStoreData(api, transferDocumentReference, transferBinaryReferenceList, resources, publicKey,
					variables);
			// references to encrypted and stored data-sets saved to variables directly
			// after processing each single data-set

			transferDocumentReference = updateDocumentReference(api, transferDocumentReference,
					transferBinaryReferenceList);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
					getDsfFhirServerAbsoluteId(api, transferDocumentReference.getIdElement()));

			logger.info("Stored DocumentReference '{}' for DMS '{}' and project-identifier '{}' in Task '{}'",
					transferDocumentReference.getId(), dmsIdentifier, projectIdentifier,
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));
			if (dicEmailEnabled)
				sendMail(api, task, projectIdentifier, consortiumIdentifier, dmsIdentifier,
						transferDocumentReference.getIdElement());

			Target target = createTarget(api, variables, consortiumIdentifier, dmsIdentifier);
			variables.setTarget(target);
		}
		catch (Exception exception)
		{
			variables.setFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES,
					transferBinaryReferenceList);

			throw new ErrorBoundaryEvent(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_SENT,
					"Encrypting and storing data-set failed - " + exception.getMessage());
		}
	}

	private PublicKey readPublicKey(ProcessPluginApi api, String consortiumIdentifier, String dmsIdentifier,
			String projectIdentifier, Task task)
	{
		String url = getEndpointUrl(api, consortiumIdentifier, dmsIdentifier);
		Optional<Bundle> publicKeyBundleOptional = keyProvider.readPublicKeyIfExists(url);

		if (publicKeyBundleOptional.isEmpty())
			throw new IllegalStateException("Could not find PublicKey Bundle of DMS '" + dmsIdentifier + "'");

		String taskReference = api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task);
		logger.debug("Downloaded PublicKey Bundle for DMS '{}' and project-identifier '{}' in Task '{}'", dmsIdentifier,
				projectIdentifier, taskReference);

		Bundle publicKeyBundle = publicKeyBundleOptional.get();
		DocumentReference documentReference = getDocumentReference(publicKeyBundle, dmsIdentifier, projectIdentifier,
				taskReference);
		Binary binary = getBinary(publicKeyBundle, dmsIdentifier, projectIdentifier, taskReference);

		PublicKey publicKey = getPublicKey(binary);
		checkHash(documentReference, publicKey, dmsIdentifier, projectIdentifier, taskReference);

		return publicKey;
	}

	private String getEndpointUrl(ProcessPluginApi api, String consortiumIdentifier, String organizationIdentifier)
	{
		return getEndpoint(api, consortiumIdentifier, organizationIdentifier).getAddress();
	}

	private DocumentReference getDocumentReference(Bundle bundle, String dmsIdentifier, String projectIdentifier,
			String taskReference)
	{
		List<DocumentReference> documentReferences = bundle.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof DocumentReference)
				.map(r -> (DocumentReference) r).toList();

		if (documentReferences.isEmpty())
			throw new IllegalArgumentException("Could not find any DocumentReference in PublicKey Bundle");

		if (documentReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReferences in PublicKey Bundle of DMS '{}' and project-identifier '{}' in Task '{}', using the first",
					documentReferences.size(), dmsIdentifier, projectIdentifier, taskReference);

		return documentReferences.getFirst();
	}

	private Binary getBinary(Bundle bundle, String dmsIdentifier, String projectIdentifier, String taskReference)
	{
		List<Binary> binaries = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof Binary).map(b -> (Binary) b).toList();

		if (binaries.isEmpty())
			throw new IllegalArgumentException("Could not find any Binary in PublicKey Bundle");

		if (binaries.size() > 1)
			logger.warn(
					"Found {} Binaries in PublicKey Bundle of DMS '{}' and project-identifier '{}' in Task '{}', using the first",
					binaries.size(), dmsIdentifier, projectIdentifier, taskReference);

		return binaries.getFirst();
	}

	private PublicKey getPublicKey(Binary binary)
	{
		try
		{
			return KeyProvider.from(binary.getContent());
		}
		catch (Exception exception)
		{
			throw new RuntimeException(
					"Could not extract PublicKey from Binary in PublicKey Bundle - " + exception.getMessage(),
					exception);
		}
	}

	private void checkHash(DocumentReference documentReference, PublicKey publicKey, String dmsIdentifier,
			String projectIdentifier, String taskReference)
	{
		long numberOfHashes = documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasHash)
				.count();

		if (numberOfHashes < 1)
			throw new RuntimeException("Could not find any sha256-hash in DocumentReference of PublicKey Bundle");

		if (numberOfHashes > 1)
			logger.warn(
					"DocumentReference of PublicKey Bundle contains {} sha256-hashes of DMS '{}' and project-identifier '{}' in Task '{}', using the first",
					numberOfHashes, dmsIdentifier, projectIdentifier, taskReference);

		byte[] documentReferenceHash = documentReference.getContentFirstRep().getAttachment().getHash();
		byte[] publicKeyHash = DigestUtils.sha256(publicKey.getEncoded());

		if (!Arrays.equals(documentReferenceHash, publicKeyHash))
			throw new RuntimeException(
					"Sha256-hash in DocumentReference does not match computed sha256-hash of Binary of PublicKey Bundle (provided: "
							+ Hex.encodeHexString(documentReferenceHash) + ", computed: "
							+ Hex.encodeHexString(publicKeyHash) + ")");
	}

	private DocumentReference createAndStoreDocumentReference(ProcessPluginApi api, String projectIdentifier,
			DocumentReference initialDocumentReference, String dmsIdentifier)
	{
		DocumentReference documentReference = new DocumentReference();
		api.getReadAccessHelper().addLocal(documentReference);
		api.getReadAccessHelper().addOrganization(documentReference, dmsIdentifier);

		documentReference.setStatus(CURRENT).setDocStatus(PRELIMINARY);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(api.getOrganizationProvider().getLocalOrganizationIdentifier()
						.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifier missing")));
		documentReference.setDate(initialDocumentReference.getDate());

		// DocumentReference.attachment has cardinality 1..*, so a dummy attachment has to be created in order
		// to use this DocumentReference as security context for the Binary resources which are created later on.
		documentReference.addContent().setAttachment(new Attachment().setTitle("dummy-attachment"));

		return api.getDsfClientProvider().getLocal().withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
				DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)).create(documentReference);
	}

	private DocumentReference updateDocumentReference(ProcessPluginApi api, DocumentReference documentReference,
			ListResource transferBinaryReferenceList)
	{
		documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);

		// Remove dummy attachment created before.
		documentReference.setContent(null);

		transferBinaryReferenceList.getEntry()
				.forEach(e -> documentReference.addContent().getAttachment().setUrl(e.getItem().getReference())
						.setContentType(e.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)));

		return api.getDsfClientProvider().getLocal().withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
				DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)).update(documentReference);
	}

	private void encryptAndStoreData(ProcessPluginApi api, DocumentReference documentReference,
			ListResource transferBinaryReferenceList, List<Resource> resources, PublicKey publicKey,
			Variables variables)
	{
		resources.forEach(r -> doEncryptAndStoreData(api, documentReference, transferBinaryReferenceList, r, publicKey,
				variables));
	}

	private void doEncryptAndStoreData(ProcessPluginApi api, DocumentReference documentReference,
			ListResource transferBinaryReferenceList, Resource resource, PublicKey publicKey, Variables variables)
	{
		String securityContext = getDsfFhirServerAbsoluteId(api, documentReference.getIdElement());

		if (resource instanceof ListResource listResource)
			encryptAndStoreDataStreams(api, listResource, transferBinaryReferenceList, publicKey, securityContext,
					variables);
		else
			encryptAndStoreDataResource(api, resource, transferBinaryReferenceList, publicKey, securityContext,
					variables);
	}

	private void encryptAndStoreDataStreams(ProcessPluginApi api, ListResource listResource,
			ListResource transferBinaryReferenceList, PublicKey publicKey, String securityContext, Variables variables)
	{
		listResource.getEntry().stream().filter(ListResource.ListEntryComponent::hasItem)
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.forEach(e -> encryptAndStoreDataStream(api, e, transferBinaryReferenceList, publicKey, securityContext,
						variables));
	}

	private void encryptAndStoreDataStream(ProcessPluginApi api, ListResource.ListEntryComponent item,
			ListResource transferBinaryReferenceList, PublicKey publicKey, String securityContext, Variables variables)
	{
		String binaryId = item.getItem().getReferenceElement().getIdPart();
		if (fhirBinaryStreamReadUseHapiBlobStorageOperation)
			binaryId += "/$binary-access-read";
		String mimeType = item.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);

		InputStream stream = encryptDataStream(api, binaryId, mimeType, publicKey);
		storeBinaryStream(api, stream, mimeType, securityContext, transferBinaryReferenceList, variables);
	}

	private void encryptAndStoreDataResource(ProcessPluginApi api, Resource resource,
			ListResource transferBinaryReferenceList, PublicKey publicKey, String securityContext, Variables variables)
	{
		Binary binaryResource = encryptDataResource(api, resource, publicKey);
		storeBinaryResource(api, binaryResource, securityContext, transferBinaryReferenceList, variables);
	}

	private InputStream encryptDataStream(ProcessPluginApi api, String binaryId, String mimetype, PublicKey publicKey)
	{
		try
		{
			InputStream stream = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId).readBinary(binaryId,
					MediaType.valueOf(mimetype));

			return api.getCryptoService().createRsaKem().encrypt(stream, publicKey);
		}
		catch (Exception exception)
		{
			throw new RuntimeException(
					"Could not encrypt data-set (inputstream) to transmit - " + exception.getMessage(), exception);
		}
	}

	private Binary encryptDataResource(ProcessPluginApi api, Resource resource, PublicKey publicKey)
	{
		try
		{
			byte[] toEncrypt = MimeTypeHelper.getData(api.getFhirContext(), resource);
			byte[] encrypted = api.getCryptoService().createRsaKem().encrypt(toEncrypt, publicKey);

			return new Binary().setData(encrypted).setContentType(MimeTypeHelper.getMimeType(resource));
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not encrypt data-set (resource) to transmit - " + exception.getMessage(),
					exception);
		}
	}

	private void storeBinaryStream(ProcessPluginApi api, InputStream inputStream, String mimeType,
			String securityContext, ListResource transferBinaryReferenceList, Variables variables)
	{
		try (InputStream in = inputStream)
		{
			MediaType mediaType = MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM);
			IdType id = api.getDsfClientProvider().getLocal()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
							DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN))
					.createBinary(in, mediaType, securityContext).getIdElement();

			createAndSaveListEntryComponent(api, transferBinaryReferenceList, id, mimeType, variables);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not store Binary - " + exception.getMessage(), exception);
		}
	}

	private void storeBinaryResource(ProcessPluginApi api, Binary binary, String securityContext,
			ListResource transferBinaryReferenceList, Variables variables)
	{
		storeBinaryStream(api, new ByteArrayInputStream(binary.getData()), binary.getContentType(), securityContext,
				transferBinaryReferenceList, variables);
	}

	private Target createTarget(ProcessPluginApi api, Variables variables, String consortiumIdentifier,
			String dmsIdentifier)
	{
		Endpoint endpoint = getEndpoint(api, consortiumIdentifier, dmsIdentifier);
		return variables.createTarget(dmsIdentifier, getEndpointIdentifierValue(endpoint), endpoint.getAddress());
	}

	private Endpoint getEndpoint(ProcessPluginApi api, String consortiumIdentifier, String organizationIdentifier)
	{
		return api.getEndpointProvider()
				.getEndpoint(NamingSystems.OrganizationIdentifier.withValue(consortiumIdentifier),
						NamingSystems.OrganizationIdentifier.withValue(organizationIdentifier),
						CodeSystems.OrganizationRole.dms())
				.orElseThrow(() -> new RuntimeException("Could not find Endpoint of organization '"
						+ consortiumIdentifier + "|" + organizationIdentifier + "'"));
	}

	private String getEndpointIdentifierValue(Endpoint endpoint)
	{
		return endpoint.getIdentifier().stream().filter(i -> NamingSystems.EndpointIdentifier.SID.equals(i.getSystem()))
				.findFirst().map(Identifier::getValue).orElseThrow(() -> new RuntimeException(
						"Endpoint '" + endpoint.getId() + "' does not contain any identifier"));
	}

	private void createAndSaveListEntryComponent(ProcessPluginApi api, ListResource transferBinaryReferenceList,
			IdType id, String mimetype, Variables variables)
	{
		ListResource.ListEntryComponent entry = transferBinaryReferenceList.addEntry();
		entry.getItem().setReference(getDsfFhirServerAbsoluteId(api, id));
		entry.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)
				.setValue(new StringType(mimetype));

		variables.setFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES,
				transferBinaryReferenceList);
	}

	private void sendMail(ProcessPluginApi api, Task task, String projectIdentifier, String consortiumIdentifier,
			String dmsIdentifier, IdType documentReferenceIdType)
	{
		String subject = "Data-set provided in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "'";
		String message = "A data-set has been successfully provided in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND + "' and Task '"
				+ api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' for DMS '" + consortiumIdentifier + "|"
				+ dmsIdentifier + "' regarding project-identifier '" + projectIdentifier
				+ "' and can be accessed using the following url:\n" + "- "
				+ getDsfFhirServerAbsoluteId(api, documentReferenceIdType);

		api.getMailService().send(subject, message);
	}

	private String getDsfFhirServerAbsoluteId(ProcessPluginApi api, IdType idType)
	{
		return new IdType(api.getDsfClientProvider().getLocal().getBaseUrl(), idType.getResourceType(),
				idType.getIdPart(), idType.getVersionIdPart()).getValue();
	}

	private DsfClient getDsfClientForFhirStore(DsfClientProvider provider, String fhirStoreId)
	{
		return provider.getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("DSF FHIR client '" + fhirStoreId + "' not configured"));
	}
}
