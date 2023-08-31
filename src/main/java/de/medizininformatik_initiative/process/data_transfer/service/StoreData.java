package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.constants.NamingSystems;
import dev.dsf.bpe.v1.variables.Target;
import dev.dsf.bpe.v1.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class StoreData extends AbstractServiceDelegate
{
	private static final Logger logger = LoggerFactory.getLogger(StoreData.class);

	public StoreData(ProcessPluginApi api)
	{
		super(api);
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		Task task = variables.getStartTask();
		byte[] bundleEncrypted = variables
				.getByteArray(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);

		logger.info(
				"Storing encrypted transferable data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			String binaryId = storeBinary(bundleEncrypted, dmsIdentifier);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE, binaryId);

			log(projectIdentifier, dmsIdentifier, binaryId, task.getId());

			Target target = createTarget(variables, dmsIdentifier);
			variables.setTarget(target);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not store data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());
			throw new RuntimeException("Could not store data-set for DMS '" + dmsIdentifier
					+ "' and project-identifier '" + projectIdentifier + "' referenced in Task with id '" + task.getId()
					+ "' - " + exception.getMessage(), exception);
		}
	}

	private String storeBinary(byte[] content, String dmsIdentifier)
	{
		MediaType mediaType = MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM);
		String securityContext = getSecurityContext(dmsIdentifier);

		try (InputStream in = new ByteArrayInputStream(content))
		{
			IdType created = api.getFhirWebserviceClientProvider().getLocalWebserviceClient().withMinimalReturn()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
					.createBinary(in, mediaType, securityContext);
			return new IdType(api.getFhirWebserviceClientProvider().getLocalWebserviceClient().getBaseUrl(),
					ResourceType.Binary.name(), created.getIdPart(), created.getVersionIdPart()).getValue();
		}
		catch (Exception exception)
		{
			logger.warn("Could not create binary - {}", exception.getMessage());
			throw new RuntimeException("Could not create binary - " + exception.getMessage(), exception);
		}
	}

	private String getSecurityContext(String dmsIdentifier)
	{
		return api.getOrganizationProvider().getOrganization(dmsIdentifier)
				.orElseThrow(() -> new RuntimeException("Could not find organization with id '" + dmsIdentifier + "'"))
				.getIdElement().toVersionless().getValue();
	}

	private void log(String projectIdentifier, String dmsIdentifier, String binaryId, String taskid)
	{
		logger.info(
				"Stored encrypted Binary with id '{}' provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				binaryId, dmsIdentifier, projectIdentifier, taskid);
	}

	private Target createTarget(Variables variables, String dmsIdentifier)
	{
		Endpoint endpoint = getEndpoint(dmsIdentifier);
		return variables.createTarget(dmsIdentifier, getEndpointIdentifierValue(endpoint), endpoint.getAddress());
	}

	private Endpoint getEndpoint(String identifier)
	{
		return api.getEndpointProvider().getEndpoint(NamingSystems.OrganizationIdentifier.withValue(
				ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM),
				NamingSystems.OrganizationIdentifier.withValue(identifier),
				new Coding().setSystem(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE)
						.setCode(ConstantsBase.CODESYSTEM_DSF_ORGANIZATION_ROLE_VALUE_DMS))
				.orElseThrow(() -> new RuntimeException(
						"Could not find Endpoint of organization with identifier '" + identifier + "'"));
	}

	private String getEndpointIdentifierValue(Endpoint endpoint)
	{
		return endpoint.getIdentifier().stream().filter(i -> NamingSystems.EndpointIdentifier.SID.equals(i.getSystem()))
				.findFirst().map(Identifier::getValue).orElseThrow(() -> new RuntimeException(
						"Endpoint with id '" + endpoint.getId() + "' does not contain any identifier"));
	}
}
