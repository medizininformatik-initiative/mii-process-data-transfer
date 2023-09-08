package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;

import java.util.List;
import java.util.Objects;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import de.medizininformatik_initiative.processes.common.util.MimeTypeHelper;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class ValidateDataDms extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(ValidateDataDms.class);

	private final MimeTypeHelper mimeTypeHelper;
	private final DataSetStatusGenerator statusGenerator;

	public ValidateDataDms(ProcessPluginApi api, MimeTypeHelper mimeTypeHelper, DataSetStatusGenerator statusGenerator)
	{
		super(api);

		this.mimeTypeHelper = mimeTypeHelper;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();

		Objects.requireNonNull(mimeTypeHelper, "mimeTypeHelper");
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getLatestTask();
		String sendingOrganization = task.getRequester().getIdentifier().getValue();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		Bundle bundle = variables.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET);

		logger.info(
				"Validating decrypted data-set from organization '{}' and project-identifier '{}' in Task with id '{}'",
				sendingOrganization, projectIdentifier, task.getId());

		try
		{
			Bundle.BundleType type = bundle.getType();
			if (!TRANSACTION.equals(type))
			{
				throw new RuntimeException("Bundle is not of type Transaction (" + type + ")");
			}

			List<Bundle.BundleEntryComponent> entries = bundle.getEntry();

			int countE = entries.size();
			if (countE != 2)
			{
				throw new RuntimeException("Bundle contains " + countE + " entries (expected 2)");
			}

			List<DocumentReference> documentReferences = entries.stream().map(Bundle.BundleEntryComponent::getResource)
					.filter(r -> r instanceof DocumentReference).map(r -> (DocumentReference) r).toList();

			long countDr = documentReferences.size();
			if (countDr != 1)
			{
				throw new RuntimeException("Bundle contains " + countDr + " DocumentReferences (expected 1)");
			}

			String identifierRequester = variables.getStartTask().getRequester().getIdentifier().getValue();
			String identifierAuthor = documentReferences.stream().filter(DocumentReference::hasAuthor)
					.flatMap(dr -> dr.getAuthor().stream()).filter(Reference::hasIdentifier)
					.map(Reference::getIdentifier).filter(Identifier::hasValue).map(Identifier::getValue).findFirst()
					.orElse("no-author");
			if (!identifierAuthor.equals(identifierRequester))
			{
				throw new RuntimeException("Requester in Task does not match author in DocumentReference ("
						+ identifierRequester + " != " + identifierAuthor + ")");
			}

			long countMi = documentReferences.stream().filter(DocumentReference::hasMasterIdentifier)
					.map(DocumentReference::getMasterIdentifier)
					.filter(mi -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(mi.getSystem()))
					.map(Identifier::getValue).filter(Objects::nonNull).count();
			if (countMi != 1)
			{
				throw new RuntimeException(
						"DocumentReference contains " + countMi + " project-identifiers (expected 1)");
			}

			List<Resource> resources = entries.stream().map(Bundle.BundleEntryComponent::getResource)
					.filter(r -> r != documentReferences.get(0)).toList();

			long countR = resources.size();
			if (countR != 1)
			{
				throw new RuntimeException("Bundle contains " + countR + " Resources (expected 1)");
			}

			Resource resource = resources.get(0);
			String mimeTypeR = mimeTypeHelper.getMimeType(resource);
			byte[] dataR = mimeTypeHelper.getData(resource);
			mimeTypeHelper.validate(dataR, mimeTypeR);

		}
		catch (Exception exception)
		{
			task.setStatus(Task.TaskStatus.FAILED);
			task.addOutput(statusGenerator.createDataSetStatusOutput(
					ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
					ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS, "Validate data-set failed"));
			variables.updateTask(task);

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE,
					"Validate data-set failed");

			logger.warn(
					"Could not validate data-set with id '{}' from organization '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE),
					task.getRequester().getIdentifier().getValue(),
					variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER), task.getId(),
					exception.getMessage());
			throw new BpmnError(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR,
					"Validate data-set - " + exception.getMessage());
		}
	}
}
