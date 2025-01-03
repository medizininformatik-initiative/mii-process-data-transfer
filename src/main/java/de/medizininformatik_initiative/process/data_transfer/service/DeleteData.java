package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;
import dev.dsf.fhir.client.BasicFhirWebserviceClient;

public class DeleteData extends AbstractServiceDelegate
{
	private static final Logger logger = LoggerFactory.getLogger(DeleteData.class);

	public DeleteData(ProcessPluginApi api)
	{
		super(api);
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		DocumentReference documentReference = variables
				.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE);

		logger.info(
				"Permanently deleting data-set provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				dmsIdentifier, projectIdentifier, task.getId(), documentReference.getId());

		try
		{
			List<IdType> attachments = getAttachmentIds(documentReference);

			deletePermanently(attachments, Binary.class);
			deletePermanently(documentReference.getIdElement(), DocumentReference.class);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not permanently delete data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments) - {}",
					dmsIdentifier, projectIdentifier, task.getId(), documentReference.getId(), exception.getMessage());

			String error = "Permanently deleting encrypted data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private List<IdType> getAttachmentIds(DocumentReference documentReference)
	{
		return documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> new IdType(a.getUrl())).toList();
	}

	private void deletePermanently(List<IdType> idTypes, Class<? extends Resource> resourceType)
	{
		idTypes.forEach(id -> deletePermanently(id, resourceType));
	}

	private void deletePermanently(IdType idType, Class<? extends Resource> resourceType)
	{
		BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);
		client.delete(resourceType, idType.getIdPart());
		client.deletePermanently(resourceType, idType.getIdPart());
	}
}
