package de.medizininformatik_initiative.process.data_transfer.service;

import java.util.List;

import org.camunda.bpm.engine.delegate.DelegateExecution;
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

		String transferDocumentReferenceLocation = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION);
		ListResource transferBinaryReferenceList = variables
				.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES);

		logger.info(
				"Permanently deleting data-set provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				dmsIdentifier, projectIdentifier, task.getId(), transferDocumentReferenceLocation);

		try
		{
			List<IdType> attachments = getAttachmentIds(transferBinaryReferenceList);

			deletePermanently(attachments, Binary.class);
			deletePermanently(new IdType(transferDocumentReferenceLocation), DocumentReference.class);
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

	private void deletePermanently(List<IdType> idTypes, Class<? extends Resource> resourceType)
	{
		idTypes.forEach(id -> deletePermanently(id, resourceType));
	}

	private void deletePermanently(IdType idType, Class<? extends Resource> resourceType)
	{
		idType = idType.toVersionless();
		if (idType.hasIdPart())
		{
			BasicFhirWebserviceClient client = api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN);
			client.delete(resourceType, idType.getIdPart());
			client.deletePermanently(resourceType, idType.getIdPart());
		}
	}
}
