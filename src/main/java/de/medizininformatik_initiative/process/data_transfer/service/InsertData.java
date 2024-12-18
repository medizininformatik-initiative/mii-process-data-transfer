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
		Bundle bundle = variables.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET);

		logger.info(
				"Inserting data-set on FHIR server with baseUrl '{}' received from organization '{}' for project-identifier '{}' in Task with id '{}'",
				fhirClientFactory.getFhirBaseUrl(), sendingOrganization, projectIdentifier, task.getId());

		try
		{
			List<IdType> createdIds = storeData(bundle, sendingOrganization, projectIdentifier, variables);

			task.addOutput(
					statusGenerator.createDataSetStatusOutput(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK,
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
							ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS));
			variables.updateTask(task);

			sendMail(task, createdIds, sendingOrganization, projectIdentifier);
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
					"Could not insert data-set with id '{}' from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE),
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());

			String error = "Insert data-set failed - " + exception.getMessage();
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private List<IdType> storeData(Bundle bundle, String sendingOrganization, String projectIdentifier,
			Variables variables)
	{
		Binary binary = bundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof Binary).map(r -> (Binary) r)
				.findFirst().orElseThrow(() -> new RuntimeException("No binary supplied"));

		IdType binaryIdType = createBinary(binary);
		IdType documentReferenceIdType = createOrUpdateDocumentReference(sendingOrganization, projectIdentifier,
				binaryIdType, binary.getContentType(), variables.getStartTask());

		List<IdType> idsOfCreatedResources = List.of(documentReferenceIdType, binaryIdType);

		idsOfCreatedResources.stream().filter(i -> ResourceType.DocumentReference.name().equals(i.getResourceType()))
				.forEach(i -> addOutputToStartTask(variables, i));

		idsOfCreatedResources.forEach(id -> toLogMessage(id, sendingOrganization, projectIdentifier));

		return idsOfCreatedResources;
	}

	private IdType createBinary(Binary binary)
	{
		if (fhirBinaryStreamWriteEnabled)
			return (IdType) fhirClientFactory.getBinaryStreamFhirClient()
					.create(new ByteArrayInputStream(binary.getContent()), binary.getContentType()).getId();

		return (IdType) fhirClientFactory.getStandardFhirClient().create(binary).getId();
	}

	private IdType createOrUpdateDocumentReference(String sendingOrganization, String projectIdentifier,
			IdType binaryIdType, String mimeType, Task task)
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
					"DocumentReference for project-identifier '{}' authored by '{}' does not exist yet, creating a new one for data-set on FHIR server with baseUrl '{}' in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(), task.getId());
			return createDocumentReference(sendingOrganization, projectIdentifier, binaryIdType, mimeType);
		}
		else
		{
			if (existingDocumentReferences.size() > 1)
				logger.warn(
						"Found more than one DocumentReference for project-identifier '{}' authored by '{}', using the first",
						projectIdentifier, sendingOrganization);

			logger.info(
					"DocumentReference for project-identifier '{}' authored by '{}' already exists, updating data-set on FHIR server with baseUrl '{}' in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClientFactory.getFhirBaseUrl(), task.getId());

			return updateDocumentReference(existingDocumentReferences.get(0), binaryIdType, mimeType);
		}
	}

	private IdType createDocumentReference(String sendingOrganization, String projectIdentifier, IdType binaryIdType,
			String mimeType)
	{
		DocumentReference documentReference = new DocumentReference().setStatus(CURRENT).setDocStatus(FINAL);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(sendingOrganization));
		documentReference.setDate(new Date());

		documentReference.addContent().getAttachment().setUrl(binaryIdType.toUnqualified().getValue())
				.setContentType(mimeType);

		return setIdBase((IdType) fhirClientFactory.getStandardFhirClient().create(documentReference).getResource()
				.getIdElement());
	}

	private IdType updateDocumentReference(DocumentReference documentReference, IdType binaryIdType, String mimeType)
	{
		documentReference.getContentFirstRep().getAttachment().setContentType(mimeType)
				.setUrl(binaryIdType.toUnqualified().getValue());

		fhirClientFactory.getStandardFhirClient().getGenericFhirClient().update().resource(documentReference)
				.withId(documentReference.getIdElement().getIdPart()).execute();

		return setIdBase(documentReference.getIdElement());
	}

	private void sendMail(Task task, List<IdType> createdIds, String sendingOrganization, String projectIdentifier)
	{
		String subject = "New data-set received in process '" + ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE
				+ "'";
		StringBuilder message = new StringBuilder("A new data-set has been stored in process '"
				+ ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE + "' for Task with id '" + task.getId()
				+ "' received from organization '" + sendingOrganization + "' for project-identifier '"
				+ projectIdentifier + "' with status code '" + ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK
				+ "' and can be accessed using the following links:\n");

		for (IdType id : createdIds)
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

	private void addOutputToStartTask(Variables variables, IdType id)
	{
		Task startTask = variables.getStartTask();
		startTask.addOutput().setValue(new Reference(id.getValue()).setType(id.getResourceType())).getType().addCoding()
				.setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION);

		variables.updateTask(startTask);
	}
}
