package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClient;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ReadData extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ReadData.class);
	private final FhirClientFactory fhirClientFactory;

	public ReadData(ProcessPluginApi api, FhirClientFactory fhirClientFactory)
	{
		super(api);
		this.fhirClientFactory = fhirClientFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(fhirClientFactory, "fhirClientFactory");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = getProjectIdentifier(task);
		String dmsIdentifier = getDmsIdentifier(task);

		FhirClient fhirClient = fhirClientFactory.getFhirClient();

		logger.info(
				"Reading data-set on FHIR server with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				fhirClient.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			DocumentReference documentReference = readDocumentReference(fhirClient, projectIdentifier, task.getId());
			Resource resource = readAttachment(fhirClient, documentReference, task.getId());

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER, dmsIdentifier);
			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DOCUMENT_REFERENCE, documentReference);
			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCE, resource);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not read data-set on FHIR server with baseUrl '{}' for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					fhirClient.getFhirBaseUrl(), dmsIdentifier, projectIdentifier, task.getId(),
					exception.getMessage());
			throw new RuntimeException(
					"Could not read data-set on FHIR server with baseUrl '" + fhirClient.getFhirBaseUrl()
							+ "' for DMS '" + dmsIdentifier + "' and project-identifier '" + projectIdentifier
							+ "' referenced in Task with id '" + task.getId() + "' - " + exception.getMessage());
		}
	}

	private String getProjectIdentifier(Task task)
	{
		List<String> identifiers = task.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER.equals(c.getCode())))
				.filter(i -> i.getValue() instanceof Identifier).map(i -> (Identifier) i.getValue())
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).toList();

		if (identifiers.size() < 1)
			throw new IllegalArgumentException("No project-identifier present in Task with id '" + task.getId() + "'");

		if (identifiers.size() > 1)
			logger.warn("Found {} project-identifiers in Task with id '{}', using only the first", identifiers.size(),
					task.getId());

		return identifiers.get(0);
	}

	private String getDmsIdentifier(Task task)
	{
		return api.getTaskHelper()
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER, Reference.class)
				.orElseThrow(
						() -> new IllegalArgumentException("No coordinating site identifier present in Task with id '"
								+ task.getId() + "', this should have been caught by resource validation"))
				.getIdentifier().getValue();
	}

	private DocumentReference readDocumentReference(FhirClient fhirClient, String projectIdentifier, String taskId)
	{
		List<DocumentReference> documentReferences = fhirClient
				.searchDocumentReferences(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER, projectIdentifier)
				.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof DocumentReference).map(r -> ((DocumentReference) r)).toList();

		if (documentReferences.size() < 1)
			throw new IllegalArgumentException("Could not find any DocumentReference for project-identifier '"
					+ projectIdentifier + "' on FHIR store with baseUrl '" + fhirClient.getFhirBaseUrl()
					+ "' referenced in Task with id '" + taskId + "'");

		if (documentReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReferences for project-identifier '{}' referenced in Task with id '{}', using first ({})",
					documentReferences.size(), projectIdentifier, taskId,
					documentReferences.get(0).getIdElement().getValue());

		return documentReferences.get(0);
	}

	private Resource readAttachment(FhirClient fhirClient, DocumentReference documentReference, String taskId)
	{
		String url = getAttachmentUrl(documentReference, taskId);
		IdType urlIdType = checkValidKdsFhirStoreUrlAndGetIdType(fhirClient, url, documentReference, taskId);

		return readAttachment(fhirClient, urlIdType);
	}

	private String getAttachmentUrl(DocumentReference documentReference, String taskId)
	{
		List<String> urls = Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasUrl)
				.map(Attachment::getUrl).toList();

		if (urls.size() < 1)
			throw new IllegalArgumentException("Could not find any attachment URLs in DocumentReference with id '"
					+ getKdsFhirStoreAbsoluteId(documentReference.getIdElement()) + "' belonging to task with id '"
					+ taskId + "'");

		if (urls.size() > 1)
			logger.warn(
					"Found {} attachment URLs in DocumentReference with id '{}' belonging to task with id '{}', using first ({})",
					urls.size(), getKdsFhirStoreAbsoluteId(documentReference.getIdElement()), taskId, urls.get(0));

		return urls.get(0);
	}

	private IdType checkValidKdsFhirStoreUrlAndGetIdType(FhirClient fhirClient, String url,
			DocumentReference documentReference, String taskId)
	{
		try
		{
			IdType idType = new IdType(url);
			String fhirBaseUrl = fhirClient.getFhirBaseUrl();

			// expecting no Base URL or, Base URL equal to KDS client Base URL
			boolean hasValidBaseUrl = !idType.hasBaseUrl() || fhirBaseUrl.equals(idType.getBaseUrl());
			boolean isResourceReference = idType.hasResourceType() && idType.hasIdPart();

			if (hasValidBaseUrl && isResourceReference)
				return idType;
			else
				throw new IllegalArgumentException("Attachment URL " + url + " in DocumentReference with id '"
						+ getKdsFhirStoreAbsoluteId(documentReference.getIdElement()) + "' belonging to task with id '"
						+ taskId + "' is not a valid KDS FHIR store reference (baseUrl if not empty must match '"
						+ fhirBaseUrl + "', resource type must be set, id must be set)");
		}
		catch (Exception exception)
		{
			logger.warn("Could not check if attachment url is a valid KDS FHIR store url - {}", exception.getMessage());
			throw new RuntimeException(
					"Could not check if attachment url is a valid KDS FHIR store url - " + exception.getMessage(),
					exception);
		}
	}

	private Resource readAttachment(FhirClient fhirClient, IdType idType)
	{
		return fhirClient.read(idType);
	}

	private String getKdsFhirStoreAbsoluteId(IdType idType)
	{
		return new IdType(fhirClientFactory.getFhirClient().getFhirBaseUrl(), idType.getResourceType(),
				idType.getIdPart(), idType.getVersionIdPart()).getValue();
	}
}
