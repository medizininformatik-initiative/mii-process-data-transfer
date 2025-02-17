package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.PRELIMINARY;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
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
import de.medizininformatik_initiative.processes.common.crypto.RsaAesGcmUtil;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.constants.NamingSystems;
import dev.dsf.bpe.v1.variables.Target;
import dev.dsf.bpe.v1.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class EncryptAndStoreData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(EncryptAndStoreData.class);

	private final KeyProvider keyProvider;
	private final FhirClientFactory fhirClientFactory;

	public EncryptAndStoreData(ProcessPluginApi api, KeyProvider keyProvider, FhirClientFactory fhirClientFactory)
	{
		super(api);
		this.keyProvider = keyProvider;
		this.fhirClientFactory = fhirClientFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		DocumentReference initialDocumentReference = variables
				.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE);
		List<Resource> resources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);

		logger.info(
				"Encrypting and storing data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			PublicKey publicKey = readPublicKey(dmsIdentifier, projectIdentifier, task.getId());
			String localOrganizationIdentifier = getLocalOrganizationIdentifier();

			DocumentReference transferDocumentReference = createAndStoreDocumentReference(projectIdentifier,
					initialDocumentReference, dmsIdentifier);
			ListResource binaryReferencesList = encryptAndStoreData(transferDocumentReference, resources, publicKey,
					localOrganizationIdentifier, dmsIdentifier);
			transferDocumentReference = updateDocumentReference(transferDocumentReference, binaryReferencesList);

			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE,
					transferDocumentReference);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
					getDsfFhirStoreAbsoluteId(transferDocumentReference.getIdElement()));

			logger.info(
					"Stored DocumentReference with id '{}' provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
					transferDocumentReference.getId(), dmsIdentifier, projectIdentifier, task.getId());

			Target target = createTarget(variables, dmsIdentifier);
			variables.setTarget(target);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not encrypt and store data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());

			String error = "Encrypting and storing data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private PublicKey readPublicKey(String dmsIdentifier, String projectIdentifier, String taskId)
	{
		String url = getEndpointUrl(dmsIdentifier);
		Optional<Bundle> publicKeyBundleOptional = keyProvider.readPublicKeyIfExists(url);

		if (publicKeyBundleOptional.isEmpty())
			throw new IllegalStateException("Could not find PublicKey Bundle of DMS organization");

		logger.debug(
				"Downloaded PublicKey Bundle for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, taskId);

		Bundle publicKeyBundle = publicKeyBundleOptional.get();
		DocumentReference documentReference = getDocumentReference(publicKeyBundle, dmsIdentifier, projectIdentifier,
				taskId);
		Binary binary = getBinary(publicKeyBundle, dmsIdentifier, projectIdentifier, taskId);

		PublicKey publicKey = getPublicKey(binary);
		checkHash(documentReference, publicKey, dmsIdentifier, projectIdentifier, taskId);

		return publicKey;
	}

	private String getEndpointUrl(String identifier)
	{
		return api.getEndpointProvider().getEndpointAddress(NamingSystems.OrganizationIdentifier.withValue(
				ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM),
				NamingSystems.OrganizationIdentifier.withValue(identifier),
				new Coding().setSystem(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE)
						.setCode(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE_VALUE_DMS))
				.orElseThrow(() -> new RuntimeException("Could not find Endpoint for DMS organization"));
	}

	private DocumentReference getDocumentReference(Bundle bundle, String dmsIdentifier, String projectIdentifier,
			String taskId)
	{
		List<DocumentReference> documentReferences = bundle.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof DocumentReference)
				.map(r -> (DocumentReference) r).toList();

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException("Could not find any DocumentReference in PublicKey Bundle");

		if (documentReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReferences in PublicKey Bundle provided by DMS '{}' and project-identifier '{}' referenced in Task with id '{}', using the first",
					documentReferences.size(), dmsIdentifier, projectIdentifier, taskId);

		return documentReferences.get(0);
	}

	private Binary getBinary(Bundle bundle, String dmsIdentifier, String projectIdentifier, String taskId)
	{
		List<Binary> binaries = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof Binary).map(b -> (Binary) b).toList();

		if (binaries.size() < 1)
			throw new IllegalArgumentException("Could not find any Binary in PublicKey Bundle");

		if (binaries.size() > 1)
			logger.warn(
					"Found {} Binaries in PublicKey Bundle of DMS '{}' and project-identifier '{}' referenced in Task with id '{}', using the first",
					binaries.size(), dmsIdentifier, projectIdentifier, taskId);

		return binaries.get(0);
	}

	private PublicKey getPublicKey(Binary binary)
	{
		try
		{
			return KeyProvider.fromBytes(binary.getContent());
		}
		catch (Exception exception)
		{
			throw new RuntimeException(
					"Could not extract PublicKey from Binary in PublicKey Bundle - " + exception.getMessage(),
					exception);
		}
	}

	private void checkHash(DocumentReference documentReference, PublicKey publicKey, String dmsIdentifier,
			String projectIdentifier, String taskId)
	{
		long numberOfHashes = documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasHash)
				.map(Attachment::getHash).count();

		if (numberOfHashes < 1)
			throw new RuntimeException("Could not find any sha256-hash in DocumentReference of PublicKey Bundle");

		if (numberOfHashes > 1)
			logger.warn(
					"DocumentReference of PublicKey Bundle contains {} sha256-hashes of DMS '{}' and project-identifier '{}' referenced in Task with id '{}', using the first",
					numberOfHashes, dmsIdentifier, projectIdentifier, taskId);

		byte[] documentReferenceHash = documentReference.getContentFirstRep().getAttachment().getHash();
		byte[] publicKeyHash = DigestUtils.sha256(publicKey.getEncoded());

		if (!Arrays.equals(documentReferenceHash, publicKeyHash))
			throw new RuntimeException(
					"Sha256-hash in DocumentReference does not match computed sha256-hash of Binary of PublicKey Bundle (provided: "
							+ Hex.encodeHexString(documentReferenceHash) + ", computed: "
							+ Hex.encodeHexString(publicKeyHash) + ")");
	}

	private String getLocalOrganizationIdentifier()
	{
		return api.getOrganizationProvider().getLocalOrganizationIdentifierValue()
				.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifierValue is null"));
	}

	private DocumentReference createAndStoreDocumentReference(String projectIdentifier,
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
						.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifier is null")));
		documentReference.setDate(initialDocumentReference.getDate());

		// DocumentReference.attachment has cardinality 1..*, so a dummy attachment has to be created in order
		// to use this DocumentReference as security context for the Binary resources which are created later on.
		documentReference.addContent().setAttachment(new Attachment().setTitle("dummy-attachment"));

		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.create(documentReference);
	}

	private DocumentReference updateDocumentReference(DocumentReference documentReference,
			ListResource binaryReferencesList)
	{
		documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);

		// Remove dummy attachment created before.
		documentReference.setContent(null);

		binaryReferencesList.getEntry()
				.forEach(e -> documentReference.addContent().getAttachment().setUrl(e.getItem().getReference())
						.setContentType(e.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)));

		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.update(documentReference);
	}

	private ListResource encryptAndStoreData(DocumentReference documentReference, List<Resource> resources,
			PublicKey publicKey, String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		ListResource binaryList = new ListResource();

		resources
				.stream().flatMap(r -> doEncryptAndStoreData(documentReference, r, publicKey,
						sendingOrganizationIdentifier, receivingOrganizationIdentifier).stream())
				.forEach(binaryList::addEntry);

		return binaryList;
	}

	private List<ListResource.ListEntryComponent> doEncryptAndStoreData(DocumentReference documentReference,
			Resource resource, PublicKey publicKey, String sendingOrganizationIdentifier,
			String receivingOrganizationIdentifier)
	{
		String securityContext = getDsfFhirStoreAbsoluteId(documentReference.getIdElement());

		List<ListResource.ListEntryComponent> binaryIds = new ArrayList<>();

		if (resource instanceof ListResource list)
			binaryIds.addAll(encryptAndStoreDataStreams(list, publicKey, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier, securityContext));
		else
			binaryIds.add(encryptAndStoreDataResource(resource, publicKey, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier, securityContext));

		return binaryIds;
	}

	private List<ListResource.ListEntryComponent> encryptAndStoreDataStreams(ListResource list, PublicKey publicKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentidfier, String securityContext)
	{
		return list.getEntry().stream().filter(e -> e.hasItem())
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.map(e -> encryptAndStoreDataStream(e, publicKey, sendingOrganizationIdentifier,
						receivingOrganizationIdentidfier, securityContext))
				.toList();
	}

	private ListResource.ListEntryComponent encryptAndStoreDataStream(ListResource.ListEntryComponent item,
			PublicKey publicKey, String sendingOrganizationIdentifier, String receivinOrganizationIdentidfier,
			String securityContext)
	{
		String mimeType = getMimeType(item);
		InputStream stream = encryptDataStream(item, publicKey, sendingOrganizationIdentifier,
				receivinOrganizationIdentidfier);
		return storeBinaryStream(stream, mimeType, securityContext);
	}

	private ListResource.ListEntryComponent encryptAndStoreDataResource(Resource resource, PublicKey publicKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier, String securityContext)
	{
		Binary binaryResource = encryptDataResource(resource, publicKey, sendingOrganizationIdentifier,
				receivingOrganizationIdentifier);
		return storeBinaryResource(binaryResource, securityContext);
	}

	private InputStream encryptDataStream(ListResource.ListEntryComponent listEntry, PublicKey publicKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		try
		{
			IdType url = (IdType) listEntry.getItem().getReferenceElement();
			String mimetype = listEntry.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);

			InputStream stream = fhirClientFactory.getBinaryStreamFhirClient().read(url, mimetype);

			return RsaAesGcmUtil.encrypt(publicKey, stream, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);
		}
		catch (Exception exception)
		{
			throw new RuntimeException(
					"Could not encrypt data-set (inputstream) to transmit - " + exception.getMessage(), exception);
		}
	}

	private Binary encryptDataResource(Resource resource, PublicKey publicKey, String sendingOrganizationIdentifier,
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
			throw new RuntimeException("Could not encrypt data-set (resource) to transmit - " + exception.getMessage(),
					exception);
		}
	}

	private ListResource.ListEntryComponent storeBinaryStream(InputStream inputStream, String mimeType,
			String securityContext)
	{
		MediaType mediaType = MediaType.valueOf(mimeType);

		try (InputStream in = inputStream)
		{
			IdType id = api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
					.createBinary(in, mediaType, securityContext).getIdElement();
			return createListEntryComponent(id, mimeType);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not store binary - " + exception.getMessage(), exception);
		}
	}

	private ListResource.ListEntryComponent storeBinaryResource(Binary binary, String securityContext)
	{
		return storeBinaryStream(new ByteArrayInputStream(binary.getData()), binary.getContentType(), securityContext);
	}

	private Target createTarget(Variables variables, String dmsIdentifier)
	{
		Endpoint endpoint = getEndpoint(dmsIdentifier);
		return variables.createTarget(dmsIdentifier, getEndpointIdentifierValue(endpoint), endpoint.getAddress());
	}

	private Endpoint getEndpoint(String identifier)
	{
		return api.getEndpointProvider().getEndpoint(NamingSystems.OrganizationIdentifier.withValue(
				ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM),
				NamingSystems.OrganizationIdentifier.withValue(identifier),
				new Coding().setSystem(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE)
						.setCode(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE_VALUE_DMS))
				.orElseThrow(() -> new RuntimeException(
						"Could not find Endpoint of organization with identifier '" + identifier + "'"));
	}

	private String getEndpointIdentifierValue(Endpoint endpoint)
	{
		return endpoint.getIdentifier().stream().filter(i -> NamingSystems.EndpointIdentifier.SID.equals(i.getSystem()))
				.findFirst().map(Identifier::getValue).orElseThrow(() -> new RuntimeException(
						"Endpoint with id '" + endpoint.getId() + "' does not contain any identifier"));
	}

	private ListResource.ListEntryComponent createListEntryComponent(IdType id, String mimetype)
	{
		ListResource.ListEntryComponent entry = new ListResource.ListEntryComponent();
		entry.getItem().setReference(getDsfFhirStoreAbsoluteId(id));
		entry.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)
				.setValue(new StringType(mimetype));

		return entry;
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

	private String getMimeType(ListResource.ListEntryComponent item)
	{
		return item.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);
	}

	private String getDsfFhirStoreAbsoluteId(IdType idType)
	{
		return new IdType(api.getFhirWebserviceClientProvider().getLocalWebserviceClient().getBaseUrl(),
				idType.getResourceType(), idType.getIdPart(), idType.getVersionIdPart()).getValue();
	}
}
