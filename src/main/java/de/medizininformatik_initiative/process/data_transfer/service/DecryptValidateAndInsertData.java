package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.FINAL;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Organization;
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
import de.medizininformatik_initiative.processes.common.mimetype.MimeTypeHelper;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.constants.NamingSystems;
import dev.dsf.bpe.v1.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class DecryptValidateAndInsertData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DecryptValidateAndInsertData.class);

	private final KeyProvider keyProvider;
	private final MimeTypeHelper mimeTypeHelper;
	private final FhirClientFactory fhirClientFactory;
	private final DataSetStatusGenerator statusGenerator;

	public DecryptValidateAndInsertData(ProcessPluginApi api, KeyProvider keyProvider, MimeTypeHelper mimeTypeHelper,
			FhirClientFactory fhirClientFactory, DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.keyProvider = keyProvider;
		this.mimeTypeHelper = mimeTypeHelper;
		this.fhirClientFactory = fhirClientFactory;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(keyProvider, "keyProvider");
		Objects.requireNonNull(mimeTypeHelper, "mimeTypeHelper");
		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();

		List<Resource> encryptedResources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);
		String localOrganizationIdentifier = getLocalOrganizationIdentifier();
		String sendingOrganizationIdentifier = getSendingOrganizationIdentifier(variables);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		logger.info(
				"Decrypting, validating and inserting data-set from organization '{}' with project-identifier '{}' referenced in Task with id '{}'",
				sendingOrganizationIdentifier, projectIdentifier, task.getId());

		try
		{
			ListResource resourceReferencesList = decryptValidateAndInsertResources(keyProvider.getPrivateKey(),
					encryptedResources, sendingOrganizationIdentifier, localOrganizationIdentifier);
			IdType documentReferenceId = createOrUpdateDocumentReference(sendingOrganizationIdentifier,
					projectIdentifier, resourceReferencesList, task).toUnqualified();

			logger.info(
					"Stored data-set in DocumentReference with id '{}' on FHIR store with baseUrl '{}' from organization '{}' with project-identifier '{}' referenced in Task with id '{}'",
					documentReferenceId, fhirClientFactory.getFhirBaseUrl(), sendingOrganizationIdentifier,
					projectIdentifier, task.getId());
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS,
					"Decrypt, validate or insert data-set failed"));
			variables.updateTask(task);

			logger.warn(
					"Could not decrypt, validate or insert data-set from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());

			String error = "Decrypt, validate or insert data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private String getLocalOrganizationIdentifier()
	{
		return api.getOrganizationProvider().getLocalOrganizationIdentifierValue()
				.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifierValue is null"));
	}

	private String getSendingOrganizationIdentifier(Variables variables)
	{
		return variables.getStartTask().getRequester().getIdentifier().getValue();
	}

	private ListResource decryptValidateAndInsertResources(PrivateKey privateKey, List<Resource> resources,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		ListResource binaryList = new ListResource();

		resources.stream().flatMap(r -> doDecryptValidateAndInsertResource(privateKey, r, sendingOrganizationIdentifier,
				receivingOrganizationIdentifier).stream()).forEach(binaryList::addEntry);

		return binaryList;
	}

	private List<ListResource.ListEntryComponent> doDecryptValidateAndInsertResource(PrivateKey privateKey,
			Resource resource, String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		List<ListResource.ListEntryComponent> binaryIds = new ArrayList<>();

		if (resource instanceof ListResource list)
			binaryIds.addAll(decryptValidateAndInsertDataStreams(list, privateKey, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier));
		else if (resource instanceof Binary binary)
			binaryIds.add(decryptValidateAndInsertDataResource(binary, privateKey, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier));
		else
			throw new RuntimeException(
					"Expected resource type Binary or List, got '" + resource.getResourceType().name() + "'");

		return binaryIds;
	}

	private List<ListResource.ListEntryComponent> decryptValidateAndInsertDataStreams(ListResource list,
			PrivateKey privateKey, String sendingOrganizationIdentifier, String receivingOrganizationIdentidfier)
	{
		return list.getEntry().stream().filter(e -> e.hasItem())
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.map(e -> decryptValidateAndInsertDataStream(e, privateKey, sendingOrganizationIdentifier,
						receivingOrganizationIdentidfier))
				.toList();
	}

	private ListResource.ListEntryComponent decryptValidateAndInsertDataStream(ListResource.ListEntryComponent item,
			PrivateKey privateKey, String sendingOrganizationIdentifier, String receivinOrganizationIdentidfier)
	{
		String mimeType = getMimeType(item);
		InputStream inputStream = decryptDataStream(item, privateKey, sendingOrganizationIdentifier,
				receivinOrganizationIdentidfier);
		validateDataStream(inputStream, mimeType);
		return insertDataStream(inputStream, mimeType);
	}

	private ListResource.ListEntryComponent decryptValidateAndInsertDataResource(Binary resource, PrivateKey privateKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		Binary binary = decryptDataResource(resource, privateKey, sendingOrganizationIdentifier,
				receivingOrganizationIdentifier);
		validateDataResource(binary);
		return insertDataResource(binary);
	}

	private InputStream decryptDataStream(ListResource.ListEntryComponent listEntry, PrivateKey privateKey,
			String sendingOrganizationIdentifier, String receivingOrganizationIdentifier)
	{
		try
		{
			IdType url = (IdType) listEntry.getItem().getReferenceElement();
			String mimetype = listEntry.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);

			InputStream stream = api.getFhirWebserviceClientProvider().getWebserviceClient(url.getBaseUrl())
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
					.readBinary(url.getIdPart(), MediaType.valueOf(mimetype));

			return RsaAesGcmUtil.decrypt(privateKey, stream, sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);
		}
		catch (Exception exception)
		{
			throw new RuntimeException(
					"Could not decrypt downloaded data-set (inputstream) - " + exception.getMessage(), exception);
		}
	}

	private Binary decryptDataResource(Binary binary, PrivateKey privateKey, String sendingOrganizationIdentifier,
			String receivingOrganizationIdentifier)
	{
		try
		{
			byte[] decrypted = RsaAesGcmUtil.decrypt(privateKey, binary.getData(), sendingOrganizationIdentifier,
					receivingOrganizationIdentifier);

			String mimeType = getMimeType(binary);
			return new Binary().setData(decrypted).setContentType(mimeType);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not decrypt downloaded data-set (resource) - " + exception.getMessage(),
					exception);
		}
	}

	private void validateDataStream(InputStream inputStream, String mimeType)
	{
		mimeTypeHelper.validate(inputStream, mimeType);
	}

	private void validateDataResource(Binary binary)
	{
		String mimeType = mimeTypeHelper.getMimeType(binary);
		byte[] data = mimeTypeHelper.getData(binary);
		mimeTypeHelper.validate(data, mimeType);
	}

	private ListResource.ListEntryComponent insertDataStream(InputStream inputStream, String mimeType)
	{
		try (InputStream in = inputStream)
		{
			IdType id = (IdType) fhirClientFactory.getBinaryStreamFhirClient().create(in, mimeType).getId();
			return createListEntryComponent(id, mimeType);
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not insert data-set attachment (inputstream) - " + exception.getMessage(),
					exception);
		}
	}

	private ListResource.ListEntryComponent insertDataResource(Binary binary)
	{
		try
		{
			Resource resource = getResourceFromBytes(binary.getData(), binary.getContentType());
			IdType id = (IdType) fhirClientFactory.getStandardFhirClient().create(resource).getId();
			return createListEntryComponent(id, binary.getContentType());
		}
		catch (Exception exception)
		{
			throw new RuntimeException("Could not insert data-set attachment (resource) - " + exception.getMessage(),
					exception);
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

	private Resource getResourceFromBytes(byte[] data, String mimeType)
	{
		if ("application/fhir+json".equals(mimeType))
			return (Resource) api.getFhirContext().newJsonParser()
					.parseResource(new String(data, StandardCharsets.UTF_8));
		else
			return new Binary().setData(data).setContentType(mimeType);
	}

	private IdType createOrUpdateDocumentReference(String sendingOrganization, String projectIdentifier,
			ListResource resourceReferencesList, Task task)
	{
		Bundle searchResult = fhirClientFactory.getStandardFhirClient().getGenericFhirClient().search()
				.forResource(DocumentReference.class)
				.where(DocumentReference.IDENTIFIER.exactly()
						.systemAndCode(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier))
				.and(DocumentReference.AUTHOR
						.hasChainedProperty("Organization",
								Organization.IDENTIFIER.exactly()
										.systemAndCode(NamingSystems.OrganizationIdentifier.SID, sendingOrganization)))
				.returnBundle(Bundle.class).execute();

		List<DocumentReference> existingDocumentReferences = searchResult.getEntry().stream()
				.filter(Bundle.BundleEntryComponent::hasResource).map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

		if (existingDocumentReferences.size() < 1)
		{
			logger.info(
					"DocumentReference for project-identifier '{}' authored by '{}' does not exist yet, creating a new one on FHIR server with baseUrl '{}' referenced in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(), task.getId());
			return createDocumentReference(sendingOrganization, projectIdentifier, resourceReferencesList);
		}
		else
		{
			if (existingDocumentReferences.size() > 1)
				logger.warn(
						"Found more than one DocumentReference for project-identifier '{}' authored by '{}' on FHIR server with baseUrl '{}' referenced in Task with id '{}', using the first '{}'",
						projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(),
						existingDocumentReferences.get(0).getId(), task.getId());

			logger.info(
					"DocumentReference for project-identifier '{}' authored by '{}' already exists, updating data-set on FHIR server with baseUrl '{}' referenced in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(), task.getId());

			return updateDocumentReference(existingDocumentReferences.get(0), resourceReferencesList);
		}
	}

	private IdType createDocumentReference(String sendingOrganization, String projectIdentifier,
			ListResource resourceReferencesList)
	{
		DocumentReference documentReference = new DocumentReference().setStatus(CURRENT).setDocStatus(FINAL);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(sendingOrganization));
		documentReference.setDate(new Date());

		addAttachmentsToDocumentReference(documentReference, resourceReferencesList);

		IdType documentReferenceId = (IdType) fhirClientFactory.getStandardFhirClient().create(documentReference)
				.getResource().getIdElement();

		return getDmsFhirStoreAbsoluteId(documentReferenceId);
	}

	private IdType updateDocumentReference(DocumentReference documentReference, ListResource resourceReferencesList)
	{
		addAttachmentsToDocumentReference(documentReference, resourceReferencesList);

		fhirClientFactory.getStandardFhirClient().getGenericFhirClient().update().resource(documentReference)
				.withId(documentReference.getIdElement().getIdPart()).execute();

		return getDmsFhirStoreAbsoluteId(documentReference.getIdElement());
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
			return "application/fhir+json";
	}

	private String getMimeType(ListResource.ListEntryComponent item)
	{
		return item.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);
	}

	private IdType getDmsFhirStoreAbsoluteId(IdType idType)
	{
		return new IdType(fhirClientFactory.getFhirBaseUrl(), idType.getResourceType(), idType.getIdPart(),
				idType.getVersionIdPart());
	}
}
