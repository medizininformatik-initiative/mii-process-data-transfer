package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.FINAL;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import de.medizininformatik_initiative.processes.common.util.MimeTypeHelper;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.service.FhirClientProvider;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class DecryptValidateAndInsertData implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DecryptValidateAndInsertData.class);

	private final String fhirStoreId;
	private final KeyProvider keyProvider;
	private final DataSetStatusGenerator statusGenerator;
	private final boolean dmseMailEnabled;

	public DecryptValidateAndInsertData(String fhirStoreId, KeyProvider keyProvider,
			DataSetStatusGenerator statusGenerator, boolean dmseMailEnabled)
	{
		this.fhirStoreId = fhirStoreId;
		this.keyProvider = keyProvider;
		this.statusGenerator = statusGenerator;
		this.dmseMailEnabled = dmseMailEnabled;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		Task task = variables.getStartTask();

		List<Resource> encryptedResources = variables
				.getFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES);
		String dicIdentifier = getDicIdentifier(variables);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		logger.info(
				"Decrypting, validating and inserting data-set from organization '{}' and project-identifier '{}' in Task '{}'",
				dicIdentifier, projectIdentifier, api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

		try
		{
			ListResource resourceReferencesList = decryptValidateAndInsertResources(api, keyProvider.getPrivateKey(),
					encryptedResources);
			IdType documentReferenceId = createOrUpdateDocumentReference(api, dicIdentifier, projectIdentifier,
					resourceReferencesList, task).toUnqualified();

			logger.info("Stored DocumentReference '{}' from organization '{}' and project-identifier '{}' in Task '{}'",
					documentReferenceId, dicIdentifier, projectIdentifier,
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));
			if (dmseMailEnabled)
				sendMail(api, task, projectIdentifier, dicIdentifier, documentReferenceId);
		}
		catch (Exception exception)
		{
			String message = "Decrypt, validate or insert data-set failed" + ConstantsBase.EXCEPTION_MESSAGE_DIVIDER
					+ exception.getMessage();
			throw new ErrorBoundaryEvent(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR, message);
		}
	}

	private String getDicIdentifier(Variables variables)
	{
		return variables.getStartTask().getRequester().getIdentifier().getValue();
	}

	private ListResource decryptValidateAndInsertResources(ProcessPluginApi api, PrivateKey privateKey,
			List<Resource> resources)
	{
		ListResource binaryList = new ListResource();

		resources.stream().flatMap(r -> doDecryptValidateAndInsertResource(api, privateKey, r).stream())
				.forEach(binaryList::addEntry);

		return binaryList;
	}

	private List<ListResource.ListEntryComponent> doDecryptValidateAndInsertResource(ProcessPluginApi api,
			PrivateKey privateKey, Resource resource)
	{
		List<ListResource.ListEntryComponent> binaryIds = new ArrayList<>();

		if (resource instanceof ListResource list)
			binaryIds.addAll(decryptValidateAndInsertDataStreams(api, list, privateKey));
		else if (resource instanceof Binary binary)
			binaryIds.add(decryptValidateAndInsertDataResource(api, binary, privateKey));
		else
			throw new RuntimeException(
					"Expected resource type Binary or List, got '" + resource.getResourceType().name() + "'");

		return binaryIds;
	}

	private List<ListResource.ListEntryComponent> decryptValidateAndInsertDataStreams(ProcessPluginApi api,
			ListResource list, PrivateKey privateKey)
	{
		return list.getEntry().stream().filter(ListResource.ListEntryComponent::hasItem)
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.map(e -> decryptValidateAndInsertDataStream(api, e, privateKey)).toList();
	}

	private ListResource.ListEntryComponent decryptValidateAndInsertDataStream(ProcessPluginApi api,
			ListResource.ListEntryComponent item, PrivateKey privateKey)
	{
		String mimeType = getMimeType(item);
		InputStream inputStream = decryptDataStream(api, item, privateKey);
		validateDataStream(api, inputStream, mimeType);
		return insertDataStream(api, inputStream, mimeType);
	}

	private ListResource.ListEntryComponent decryptValidateAndInsertDataResource(ProcessPluginApi api, Binary resource,
			PrivateKey privateKey)
	{
		Binary binary = decryptDataResource(api, resource, privateKey);
		validateDataResource(api, binary);
		return insertDataResource(api, binary);
	}

	private InputStream decryptDataStream(ProcessPluginApi api, ListResource.ListEntryComponent listEntry,
			PrivateKey privateKey)
	{
		try
		{
			IdType url = (IdType) listEntry.getItem().getReferenceElement();

			InputStream inputStream = api.getDsfClientProvider().getByEndpointUrl(url.getBaseUrl())
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
							DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN))
					.readBinary(url.getIdPart(), MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM));

			return api.getCryptoService().createRsaKem().decrypt(inputStream, privateKey);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not decrypt downloaded data-set (inputstream)"
					+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + exception.getMessage(), exception);
		}
	}

	private Binary decryptDataResource(ProcessPluginApi api, Binary binary, PrivateKey privateKey)
	{
		try
		{
			byte[] decrypted = api.getCryptoService().createRsaKem().decrypt(binary.getData(), privateKey);

			String mimeType = getMimeType(binary);
			return new Binary().setData(decrypted).setContentType(mimeType);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not decrypt downloaded data-set (resource)"
					+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + exception.getMessage(), exception);
		}
	}

	private void validateDataStream(ProcessPluginApi api, InputStream inputStream, String mimeType)
	{
		if (!inputStream.markSupported())
			inputStream = new BufferedInputStream(inputStream);

		api.getMimeTypeService().validateWithException(inputStream, mimeType);
	}

	private void validateDataResource(ProcessPluginApi api, Binary binary)
	{
		String mimeType = MimeTypeHelper.getMimeType(binary);
		byte[] data = MimeTypeHelper.getData(api.getFhirContext(), binary);
		api.getMimeTypeService().validateWithException(data, mimeType);
	}

	private ListResource.ListEntryComponent insertDataStream(ProcessPluginApi api, InputStream inputStream,
			String mimeType)
	{
		try (InputStream in = inputStream)
		{
			DsfClient client = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId);
			IdType id = client.withMinimalReturn().createBinary(in, MediaType.valueOf(mimeType),
					client.getBaseUrl() + "/DocumentReference");
			return createListEntryComponent(id, mimeType);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not insert data-set attachment (inputstream)"
					+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + exception.getMessage(), exception);
		}
	}

	private ListResource.ListEntryComponent insertDataResource(ProcessPluginApi api, Binary binary)
	{
		try
		{
			Resource resource = getResourceFromBytes(api, binary.getData(), binary.getContentType());
			IdType id = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId).create(resource)
					.getIdElement();
			return createListEntryComponent(id, binary.getContentType());
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not insert data-set attachment (resource)"
					+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + exception.getMessage(), exception);
		}
	}

	private ListResource.ListEntryComponent createListEntryComponent(IdType id, String mimetype)
	{
		ListResource.ListEntryComponent entry = new ListResource.ListEntryComponent();
		entry.getItem().setReference(id.getValue());
		entry.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)
				.setValue(new StringType(mimetype));

		return entry;
	}

	private Resource getResourceFromBytes(ProcessPluginApi api, byte[] data, String mimeType)
	{
		if ("application/fhir+xml".equals(mimeType))
			return (Resource) api.getFhirContext().newXmlParser()
					.parseResource(new String(data, StandardCharsets.UTF_8));
		else if ("application/fhir+json".equals(mimeType))
			return (Resource) api.getFhirContext().newJsonParser()
					.parseResource(new String(data, StandardCharsets.UTF_8));
		else
			return new Binary().setData(data).setContentType(mimeType);
	}

	private IdType createOrUpdateDocumentReference(ProcessPluginApi api, String sendingOrganization,
			String projectIdentifier, ListResource resourceReferencesList, Task task)
	{
		List<DocumentReference> existingDocumentReferences = searchExistingDocumentReferences(api, sendingOrganization,
				projectIdentifier, task);

		if (existingDocumentReferences.isEmpty())
		{
			logger.info(
					"DocumentReference of organization {} and project-identifier '{}' does not exist yet, creating a new one on FHIR server '{}' in Task '{}'",
					sendingOrganization, projectIdentifier, fhirStoreId,
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));
			return createDocumentReference(api, sendingOrganization, projectIdentifier, resourceReferencesList);
		}
		else
		{
			if (existingDocumentReferences.size() > 1)
				logger.warn(
						"Found more than one DocumentReference of organization '{}' for project-identifier '{}' on FHIR server '{}' in Task '{}', using the first '{}'",
						sendingOrganization, projectIdentifier, fhirStoreId,
						existingDocumentReferences.getFirst().getId(),
						api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

			logger.info(
					"DocumentReference of organization '{}' and project-identifier '{}' already exists, updating data-set on FHIR server '{}' in Task '{}'",
					sendingOrganization, projectIdentifier, fhirStoreId,
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

			return updateDocumentReference(api, existingDocumentReferences.getFirst(), resourceReferencesList);
		}
	}

	private List<DocumentReference> searchExistingDocumentReferences(ProcessPluginApi api, String sendingOrganization,
			String projectIdentifier, Task task)
	{
		// workaround since not all fhir server used in MII support DocumentReference.author:identifier or
		// DocumentReference.author:Organization.identifier search parameters. Therefore, filtering for author
		// after loading all DocumentReferences for given project-identifier
		try
		{
			IGenericClient client = getFhirClientForFhirStore(api.getFhirClientProvider(), fhirStoreId);

			Bundle searchResult = client.search().forResource(DocumentReference.class)
					.where(DocumentReference.IDENTIFIER.exactly()
							.systemAndCode(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier))
					.returnBundle(Bundle.class).execute();

			List<Bundle.BundleEntryComponent> entries = new ArrayList<>(searchResult.getEntry());
			while (searchResult.getLink(IBaseBundle.LINK_NEXT) != null)
			{
				searchResult = client.loadPage().next(searchResult).execute();
				entries.addAll(searchResult.getEntry());
			}

			return entries.stream().filter(Bundle.BundleEntryComponent::hasResource)
					.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof DocumentReference)
					.map(r -> (DocumentReference) r)
					.filter(d -> d.getAuthor().stream().anyMatch(a -> a.hasIdentifier()
							&& NamingSystems.OrganizationIdentifier.SID.equals(a.getIdentifier().getSystem())
							&& sendingOrganization != null && sendingOrganization.equals(a.getIdentifier().getValue())))
					.toList();
		}
		catch (Exception exception)
		{
			logger.warn(
					"Error while searching DocumentReferences of organization '{}' for project-identifier '{}' on FHIR server '{}' in Task '{}'- {}",
					sendingOrganization, projectIdentifier, fhirStoreId,
					api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task), exception.getMessage());
			return List.of();
		}
	}

	private IdType createDocumentReference(ProcessPluginApi api, String sendingOrganization, String projectIdentifier,
			ListResource resourceReferencesList)
	{
		DocumentReference documentReference = new DocumentReference().setStatus(CURRENT).setDocStatus(FINAL);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(sendingOrganization));
		documentReference.setDate(new Date());

		addAttachmentsToDocumentReference(documentReference, resourceReferencesList);

		IdType documentReferenceId = (IdType) getFhirClientForFhirStore(api.getFhirClientProvider(), fhirStoreId)
				.create().resource(documentReference).execute().getId();

		return getDmsFhirStoreAbsoluteId(api, documentReferenceId);
	}

	private IdType updateDocumentReference(ProcessPluginApi api, DocumentReference documentReference,
			ListResource resourceReferencesList)
	{
		addAttachmentsToDocumentReference(documentReference, resourceReferencesList);

		getFhirClientForFhirStore(api.getFhirClientProvider(), fhirStoreId).update().resource(documentReference)
				.withId(documentReference.getIdElement().getIdPart()).execute();

		return getDmsFhirStoreAbsoluteId(api, documentReference.getIdElement());
	}

	private void addAttachmentsToDocumentReference(DocumentReference documentReference,
			ListResource resourceReferencesList)
	{
		documentReference.setContent(null);
		resourceReferencesList.getEntry().forEach(e -> addAttachmentToDocumentReference(documentReference, e));
	}

	private void addAttachmentToDocumentReference(DocumentReference documentReference,
			ListResource.ListEntryComponent entry)
	{
		documentReference.addContent().getAttachment().setContentType(getMimeType(entry))
				.setUrl(entry.getItem().getReferenceElement().getValue());
	}

	private String getMimeType(Resource resource)
	{
		if (resource instanceof Binary binary)
			return binary.getContentType();
		else
			return "application/fhir+xml";
	}

	private String getMimeType(ListResource.ListEntryComponent item)
	{
		return item.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);
	}

	private IdType getDmsFhirStoreAbsoluteId(ProcessPluginApi api, IdType idType)
	{
		return new IdType(getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId).getBaseUrl(),
				idType.getResourceType(), idType.getIdPart(), idType.getVersionIdPart());
	}

	private DsfClient getDsfClientForFhirStore(DsfClientProvider provider, String fhirStoreId)
	{
		return provider.getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("DSF client '" + fhirStoreId + "' not configured"));
	}

	private IGenericClient getFhirClientForFhirStore(FhirClientProvider provider, String fhirStoreId)
	{
		return provider.getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("FHIR client '" + fhirStoreId + "' not configured"));
	}

	private void sendMail(ProcessPluginApi api, Task task, String projectIdentifier, String dicIdentifier,
			IdType documentReferenceId)
	{
		String subject = "Data-set successfully received in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		String message = "A data-set has been successfully downloaded and inserted in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' and Task '"
				+ api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task) + "' from organization '" + dicIdentifier
				+ "' regarding project-identifier '" + projectIdentifier + "' with status code '"
				+ ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK
				+ "' and can be accessed using the following url:\n" + "- "
				+ getDsfFhirServerAbsoluteId(api, documentReferenceId);

		api.getMailService().send(subject, message);
	}

	private String getDsfFhirServerAbsoluteId(ProcessPluginApi api, IdType idType)
	{
		return new IdType(api.getDsfClientProvider().getLocal().getBaseUrl(), idType.getResourceType(),
				idType.getIdPart(), idType.getVersionIdPart()).getValue();
	}
}
