package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;

import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.BasicDsfClient;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.variables.Variables;

public class DeleteData implements ServiceTask
{
	private static final Logger logger = LoggerFactory.getLogger(DeleteData.class);

	public DeleteData()
	{
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws ErrorBoundaryEvent, Exception
	{
		Task task = variables.getStartTask();
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		String transferDocumentReferenceLocation = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION);
		ListResource transferBinaryReferenceList = variables
				.getFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES);

		logger.info(
				"Permanently deleting data-set provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				dmsIdentifier, projectIdentifier, task.getId(), transferDocumentReferenceLocation);

		try
		{
			List<IdType> attachments = getAttachmentIds(transferBinaryReferenceList);

			deletePermanently(api, attachments, Binary.class);
			deletePermanently(api, new IdType(transferDocumentReferenceLocation), DocumentReference.class);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not permanently delete data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments) - {}",
					dmsIdentifier, projectIdentifier, task.getId(), transferDocumentReferenceLocation,
					exception.getMessage());

			throw new RuntimeException("Permanently deleting encrypted data-set failed - " + exception.getMessage(),
					exception);
		}
	}

	private List<IdType> getAttachmentIds(ListResource transferBinaryReferenceList)
	{
		return transferBinaryReferenceList.getEntry().stream().filter(ListResource.ListEntryComponent::hasItem)
				.map(ListResource.ListEntryComponent::getItem).filter(Reference::hasReference)
				.map(i -> (IdType) i.getReferenceElement()).toList();
	}

	private void deletePermanently(ProcessPluginApi api, List<IdType> idTypes, Class<? extends Resource> resourceType)
	{
		idTypes.forEach(id -> deletePermanently(api, id, resourceType));
	}

	private void deletePermanently(ProcessPluginApi api, IdType idType, Class<? extends Resource> resourceType)
	{
		idType = idType.toVersionless();
		if (idType.hasIdPart())
		{
			BasicDsfClient client = api.getDsfClientProvider().getLocal().withRetry(
					ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
					DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN));
			client.delete(resourceType, idType.getIdPart());
			client.deletePermanently(resourceType, idType.getIdPart());
		}
	}
}
