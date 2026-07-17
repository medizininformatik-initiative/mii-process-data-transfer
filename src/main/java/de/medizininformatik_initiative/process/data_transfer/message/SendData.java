package de.medizininformatik_initiative.process.data_transfer.message;

import java.util.List;
import java.util.function.Function;

import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.ParameterComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.activity.RetryTaskSender;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.MessageSendTask;
import dev.dsf.bpe.v2.activity.task.TaskSender;
import dev.dsf.bpe.v2.activity.values.SendTaskValues;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.bpe.v2.error.MessageSendTaskErrorHandler;
import dev.dsf.bpe.v2.error.impl.ExceptionToErrorBoundaryEventTranslationErrorHandler;
import dev.dsf.bpe.v2.variables.Target;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public class SendData implements MessageSendTask
{
	private static final Logger logger = LoggerFactory.getLogger(SendData.class);

	public SendData()
	{
	}

	@Override
	public List<ParameterComponent> getAdditionalInputParameters(ProcessPluginApi api, Variables variables,
			SendTaskValues sendTaskValues, Target target)
	{
		String version = api.getProcessPluginDefinition().getResourceVersion();
		String documentReferenceId = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION);

		ParameterComponent documentReferenceComponent = new ParameterComponent();
		documentReferenceComponent.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setVersion(version)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION);
		documentReferenceComponent.setValue(
				new Reference().setType(ResourceType.DocumentReference.name()).setReference(documentReferenceId));

		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		Task.ParameterComponent projectIdentifierComponent = new Task.ParameterComponent();
		projectIdentifierComponent.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setVersion(version).setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER);
		projectIdentifierComponent.setValue(new Identifier()
				.setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER).setValue(projectIdentifier));

		String consortiumIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER);

		Task.ParameterComponent consortiumIdentifierComponent = new Task.ParameterComponent();
		consortiumIdentifierComponent.getType().addCoding().setSystem(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER)
				.setVersion(version)
				.setCode(ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_CONSORTIUM_IDENTIFIER);
		consortiumIdentifierComponent.setValue(new Reference().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue(consortiumIdentifier)));

		return List.of(documentReferenceComponent, projectIdentifierComponent, consortiumIdentifierComponent);
	}

	@Override
	public TaskSender getTaskSender(ProcessPluginApi api, Variables variables, SendTaskValues sendTaskValues)
	{
		return new RetryTaskSender(api, variables, sendTaskValues, getBusinessKeyStrategy(),
				(target) -> getAdditionalInputParameters(api, variables, sendTaskValues, target));
	}

	@Override
	public MessageSendTaskErrorHandler getErrorHandler()
	{
		Function<Exception, String> errorCodeTranslator = (exception) ->
		{
			String errorCode = ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_REACHABLE;
			if (exception instanceof WebApplicationException webApplicationException
					&& webApplicationException.getResponse() != null
					&& webApplicationException.getResponse().getStatus() == Response.Status.FORBIDDEN.getStatusCode())
			{
				errorCode = ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_ALLOWED;
			}

			logger.error("Send data-set failed with error code '{}' - {} - throwing error boundary event", errorCode,
					exception.getMessage());
			return errorCode;
		};

		Function<Exception, String> errorMessageTranslator = (exception) -> "Send data-set failed"
				+ ConstantsBase.EXCEPTION_MESSAGE_DIVIDER + exception.getMessage();

		return new ExceptionToErrorBoundaryEventTranslationErrorHandler(errorCodeTranslator, errorMessageTranslator);
	}
}
