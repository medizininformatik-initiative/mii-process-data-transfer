package de.medizininformatik_initiative.process.data_transfer.bpe.start;

import java.util.Date;
import java.util.UUID;

import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskIntent;
import org.hl7.fhir.r4.model.Task.TaskStatus;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.DataTransferProcessPluginDefinition;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.start.ExampleStarter;
import dev.dsf.bpe.v1.constants.CodeSystems;
import dev.dsf.bpe.v1.constants.NamingSystems;

public class DataSendExampleStarter
{
	private static final String DIC_URL = "https://dic1/fhir";
	private static final String DIC_IDENTIFIER = "Test_DIC1";

	public static void main(String[] args) throws Exception
	{
		ExampleStarter.forServer(args, DIC_URL).startWith(task());
	}

	private static Task task()
	{
		var def = new DataTransferProcessPluginDefinition();

		Task task = new Task();
		task.setIdElement(new IdType("urn:uuid:" + UUID.randomUUID().toString()));

		task.getMeta().addProfile(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START + "|" + def.getResourceVersion());
		task.setInstantiatesUri(
				ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_PROCESS_URI + "|" + def.getResourceVersion());
		task.setStatus(TaskStatus.REQUESTED);
		task.setIntent(TaskIntent.ORDER);
		task.setAuthoredOn(new Date());
		task.getRequester().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(DIC_IDENTIFIER));
		task.getRestriction().addRecipient().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(DIC_IDENTIFIER));

		task.addInput().setValue(new StringType(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME))
				.getType().addCoding(CodeSystems.BpmnMessage.messageName());

		task.addInput()
				.setValue(new Reference().setIdentifier(NamingSystems.OrganizationIdentifier.withValue("Test_DMS"))
						.setType(ResourceType.Organization.name()))
				.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER);

		task.addInput()
				.setValue(new Identifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
						.setValue("Test_PROJECT_CSV"))
				.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER);

		return task;
	}
}
