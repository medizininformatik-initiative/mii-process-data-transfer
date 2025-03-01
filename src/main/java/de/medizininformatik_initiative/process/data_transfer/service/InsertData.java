package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClient;
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
	private final DataSetStatusGenerator statusGenerator;

	public InsertData(ProcessPluginApi api, FhirClientFactory fhirClientFactory, DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.fhirClientFactory = fhirClientFactory;
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

		FhirClient fhirClient = fhirClientFactory.getFhirClient();

		logger.info(
				"Inserting data-set on FHIR server with baseUrl '{}' received from organization '{}' for project-identifier '{}' in Task with id '{}'",
				fhirClient.getFhirBaseUrl(), sendingOrganization, projectIdentifier, task.getId());

		try
		{
			List<IdType> createdIds = storeData(fhirClient, bundle, sendingOrganization, projectIdentifier, variables);

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
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE, error);
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR, error, exception);
		}
	}

	private List<IdType> storeData(FhirClient fhirClient, Bundle bundle, String sendingOrganization,
			String projectIdentifier, Variables variables)
	{
		Bundle transactionBundle = checkAndAdaptBundleForExistingData(fhirClient, bundle, sendingOrganization,
				projectIdentifier, variables.getStartTask());
		Bundle stored = fhirClient.executeTransaction(transactionBundle);

		List<IdType> idsOfCreatedResources = stored.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResponse)
				.map(Bundle.BundleEntryComponent::getResponse).map(Bundle.BundleEntryResponseComponent::getLocation)
				.map(IdType::new).map(this::setIdBase).toList();

		idsOfCreatedResources.stream().filter(i -> ResourceType.DocumentReference.name().equals(i.getResourceType()))
				.forEach(i -> addOutputToStartTask(variables, i));

		idsOfCreatedResources.forEach(id -> toLogMessage(id, sendingOrganization, projectIdentifier));

		return idsOfCreatedResources;
	}

	private Bundle checkAndAdaptBundleForExistingData(FhirClient fhirClient, Bundle bundle, String sendingOrganization,
			String projectIdentifier, Task task)
	{
		List<DocumentReference> existingDocumentReferences = searchExistingDocumentReferences(fhirClient,
				sendingOrganization, projectIdentifier, task.getId());

		if (existingDocumentReferences.size() < 1)
		{
			logger.info(
					"DocumentReference for project-identifier '{}' authored by '{}' does not yet exist, creating a new data-set on FHIR server with baseUrl '{}' in Task with id '{}'",
					projectIdentifier, sendingOrganization, fhirClient.getFhirBaseUrl(), task.getId());
			return bundle;
		}

		if (existingDocumentReferences.size() > 1)
			logger.warn(
					"Found more than one DocumentReference for project-identifier '{}' authored by '{}', using the first",
					projectIdentifier, sendingOrganization);

		logger.info(
				"DocumentReference for project-identifier '{}' authored by '{}' already exists, updating data-set on FHIR server with baseUrl '{}' in Task with id '{}'",
				projectIdentifier, sendingOrganization, fhirClient.getFhirBaseUrl(), task.getId());

		DocumentReference existingDocumentReference = existingDocumentReferences.get(0);
		String existingDocumentReferenceId = existingDocumentReference.getIdElement().getIdPart();

		bundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.filter(e -> e.getResource() instanceof DocumentReference)
				.filter(Bundle.BundleEntryComponent::hasRequest).filter(Bundle.BundleEntryComponent::hasResource)
				.forEach(e ->
				{
					e.getRequest().setMethod(Bundle.HTTPVerb.PUT)
							.setUrl(ResourceType.DocumentReference.name() + "/" + existingDocumentReferenceId);
					e.getResource().setId(existingDocumentReferenceId);
				});

		return bundle;
	}

	private List<DocumentReference> searchExistingDocumentReferences(FhirClient fhirClient, String sendingOrganization,
			String projectIdentifier, String taskId)
	{
		// workaround since not all fhir server used in MII support DocumentReference.author:identifier or
		// DocumentReference.author:Organization.identifier search parameters. Therefore filtering for author
		// after loading all DocumentReferences for given project-identifier
		try
		{
			List<Bundle.BundleEntryComponent> entries = new ArrayList<>();

			Bundle searchResult = fhirClient.getGenericFhirClient().search().forResource(DocumentReference.class)
					.where(DocumentReference.IDENTIFIER.exactly()
							.systemAndCode(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier))
					.returnBundle(Bundle.class).execute();
			entries.addAll(searchResult.getEntry());

			while (searchResult.getLink(IBaseBundle.LINK_NEXT) != null)
			{
				searchResult = fhirClient.getGenericFhirClient().loadPage().next(searchResult).execute();
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
					"Error while searching for existing DocumentReferences for project-identifier '{}' authored by '{}' on FHIR server with baseUrl '{}' in Task with id '{}'- {}",
					projectIdentifier, sendingOrganization, fhirClient.getFhirBaseUrl(), taskId,
					exception.getMessage());
			return List.of();
		}
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
		String fhirBaseUrl = fhirClientFactory.getFhirClient().getFhirBaseUrl();
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
