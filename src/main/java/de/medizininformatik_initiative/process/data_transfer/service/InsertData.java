package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.FINAL;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.constants.NamingSystems;
import dev.dsf.bpe.v1.variables.Variables;

public class InsertData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(InsertData.class);

	private final FhirClientFactory fhirClientFactory;
	private final boolean fhirBinaryStreamWriteEnabled;
	private final DataSetStatusGenerator statusGenerator;

	public InsertData(ProcessPluginApi api, FhirClientFactory fhirClientFactory, boolean fhirBinaryStreamWriteEnabled,
			DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.fhirClientFactory = fhirClientFactory;
		this.fhirBinaryStreamWriteEnabled = fhirBinaryStreamWriteEnabled;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String sendingOrganization = task.getRequester().getIdentifier().getValue();
		List<Resource> resources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);

		logger.info(
				"Inserting data-set on FHIR server with baseUrl '{}' received from organization '{}' for project-identifier '{}' in Task with id '{}'",
				fhirClientFactory.getFhirBaseUrl(), sendingOrganization, projectIdentifier, task.getId());

		try
		{
			// TODO: does not work with Blaze FHIR server
			insertData(resources, sendingOrganization, projectIdentifier, task);

			task.addOutput(
					statusGenerator.createDataSetStatusOutput(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK,
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));

			variables.updateTask(task);
		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Insert data-set failed"));
			variables.updateTask(task);

			logger.warn(
					"Could not insert data-set from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());

			String error = "Insert data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private void insertData(List<Resource> resources, String sendingOrganization, String projectIdentifier,
			Task startTask)
	{
		List<Resource> attachments = createResources(resources);
		List<IdType> attachmentIdTypes = attachments.stream().map(Resource::getIdElement).toList();
		attachmentIdTypes.forEach(id -> toLogMessage(id, sendingOrganization, projectIdentifier));

		IdType documentReferenceIdType = createOrUpdateDocumentReference(sendingOrganization, projectIdentifier,
				resources, startTask);
		toLogMessage(documentReferenceIdType, sendingOrganization, projectIdentifier);

		addOutputToStartTask(startTask, documentReferenceIdType);
		sendMail(startTask, documentReferenceIdType, attachmentIdTypes, sendingOrganization, projectIdentifier);
	}

	private List<Resource> createResources(List<Resource> resource)
	{
		return resource.stream().map(this::createResource).toList();
	}

	private Resource createResource(Resource resource)
	{
		if (fhirBinaryStreamWriteEnabled && resource instanceof Binary binary)
		{
			IdType id = (IdType) fhirClientFactory.getBinaryStreamFhirClient()
					.create(new ByteArrayInputStream(binary.getContent()), binary.getContentType()).getId();
			resource.setIdElement(id);
			return resource;
		}

		IdType id = (IdType) fhirClientFactory.getStandardFhirClient().create(resource).getId();
		resource.setIdElement(id);
		return resource;
	}

	private IdType createOrUpdateDocumentReference(String sendingOrganization, String projectIdentifier,
			List<Resource> resources, Task task)
	{
		Bundle searchResult = fhirClientFactory.getStandardFhirClient().getGenericFhirClient().search()
				.forResource(DocumentReference.class)
				.where(DocumentReference.IDENTIFIER.exactly()
						.systemAndCode(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier))
				.and(DocumentReference.AUTHOR.hasChainedProperty(Organization.IDENTIFIER.exactly()
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
			return createDocumentReference(sendingOrganization, projectIdentifier, resources);
		}
		else
		{
			if (existingDocumentReferences.size() > 1)
				logger.warn(
						"Found more than one DocumentReference for project-identifier '{}' authored by '{}', using the first",
						projectIdentifier, sendingOrganization);

			logger.info(
					"DocumentReference for project-identifier '{}' authored by '{}' already exists, updating data-set on FHIR server with baseUrl '{}' referenced in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(), task.getId());

			return updateDocumentReference(existingDocumentReferences.get(0), resources);
		}
	}

	private IdType createDocumentReference(String sendingOrganization, String projectIdentifier,
			List<Resource> attachments)
	{
		DocumentReference documentReference = new DocumentReference().setStatus(CURRENT).setDocStatus(FINAL);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(sendingOrganization));
		documentReference.setDate(new Date());

		addAttachmentsToDocumentReference(documentReference, attachments);

		IdType documentReferenceId = (IdType) fhirClientFactory.getStandardFhirClient().create(documentReference)
				.getResource().getIdElement();
		return setIdBase(documentReferenceId);
	}

	private IdType updateDocumentReference(DocumentReference documentReference, List<Resource> attachments)
	{
		addAttachmentsToDocumentReference(documentReference, attachments);

		fhirClientFactory.getStandardFhirClient().getGenericFhirClient().update().resource(documentReference)
				.withId(documentReference.getIdElement().getIdPart()).execute();
		return setIdBase(documentReference.getIdElement());
	}

	private void addAttachmentsToDocumentReference(DocumentReference documentReference, List<Resource> attachments)
	{
		documentReference.setContent(null);
		attachments.forEach(a -> addAttachmentToDocumentReference(documentReference, a));
	}

	private void addAttachmentToDocumentReference(DocumentReference documentReference, Resource attachment)
	{
		documentReference.addContent().getAttachment().setContentType(getContentType(attachment))
				.setUrl(attachment.getIdElement().toUnqualified().getValue());
	}

	private String getContentType(Resource resource)
	{
		if (resource instanceof Binary binary)
			return binary.getContentType();
		else
			return "application/fhir+xml";
	}

	private void sendMail(Task task, IdType documentReferenceId, List<IdType> attachmentIds, String sendingOrganization,
			String projectIdentifier)
	{
		String subject = "New DocumentReference with attachments received in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "'";
		StringBuilder message = new StringBuilder(
				"A new DocumentReference with attachments has been stored in process '"
						+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' for Task with id '" + task.getId()
						+ "' received from organization '" + sendingOrganization + "' for project-identifier '"
						+ projectIdentifier + "' with status code '"
						+ ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK
						+ "' and can be accessed using the following links:\n");

		message.append(documentReferenceId.getValue()).append("\n");
		for (IdType id : attachmentIds)
			message.append(id.getValue()).append("\n");

		api.getMailService().send(subject, message.toString());
	}

	private IdType setIdBase(IdType idType)
	{
		String fhirBaseUrl = fhirClientFactory.getFhirBaseUrl();
		return new IdType(fhirBaseUrl, idType.getResourceType(), idType.getIdPart(), idType.getVersionIdPart());
	}

	private void toLogMessage(IdType idType, String sendingOrganization, String projectIdentifier)
	{
		logger.info(
				"Stored {} with id '{}' on FHIR server with baseUrl '{}' received from organization '{}' for project-identifier '{}'",
				idType.getResourceType(), idType.getIdPart(), idType.getBaseUrl(), sendingOrganization,
				projectIdentifier);
	}

	private void addOutputToStartTask(Task startTask, IdType id)
	{
		startTask.addOutput().setValue(new Reference(id.getValue()).setType(id.getResourceType())).getType().addCoding()
				.setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION);
	}
}
