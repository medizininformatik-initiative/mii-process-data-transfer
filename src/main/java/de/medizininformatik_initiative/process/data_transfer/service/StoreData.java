package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.PRELIMINARY;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
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
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		DocumentReference initialDocumentReference = variables
				.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE);
		List<Binary> encryptedResources = variables
				.getResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCES);

		logger.info(
				"Storing encrypted data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, task.getId());

		try
		{
			DocumentReference transferDocumentReference = createAndStoreDocumentReference(projectIdentifier,
					initialDocumentReference, dmsIdentifier);
			List<String> binaryIds = storeBinaries(encryptedResources, transferDocumentReference);
			transferDocumentReference = updateDocumentReference(transferDocumentReference, binaryIds);

			variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE,
					transferDocumentReference);
			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
					getDsfFhirStoreAbsoluteId(transferDocumentReference.getIdElement()));

			log(projectIdentifier, dmsIdentifier, transferDocumentReference.getId(), task.getId());

			Target target = createTarget(variables, dmsIdentifier);
			variables.setTarget(target);
		}
		catch (Exception exception)
		{
			logger.warn(
					"Could not store data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}' - {}",
					dmsIdentifier, projectIdentifier, task.getId(), exception.getMessage());

			String error = "Store data-set failed - " + exception.getMessage();
			throw new RuntimeException(error, exception);
		}
	}

	private DocumentReference createAndStoreDocumentReference(String projectIdentifier,
			DocumentReference initialDocumentReference, String dmsIdentifier)
	{
		DocumentReference documentReference = new DocumentReference();
		api.getReadAccessHelper().addLocal(documentReference);
		api.getReadAccessHelper().addOrganization(documentReference, dmsIdentifier);

		documentReference.setStatus(CURRENT).setDocStatus(PRELIMINARY);
		documentReference.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReference.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(api.getOrganizationProvider().getLocalOrganizationIdentifier()
						.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifier is null")));
		documentReference.setDate(initialDocumentReference.getDate());

		// DocumentReference.attachment has cardinality 1..*, so a dummy attachment has to be created in order
		// to use this DocumentReference as security context for the Binary resources which are created later on.
		documentReference.addContent().setAttachment(new Attachment().setTitle("dummy-attachment"));

		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.create(documentReference);
	}

	private List<String> storeBinaries(List<Binary> binaries, DocumentReference documentReference)
	{
		String securityContext = getDsfFhirStoreAbsoluteId(documentReference.getIdElement());
		return binaries.stream().map(b -> storeBinary(b, securityContext)).toList();
	}

	private String storeBinary(Binary binary, String securityContext)
	{
		MediaType mediaType = MediaType.valueOf(binary.getContentType());

		try (InputStream in = new ByteArrayInputStream(binary.getContent()))
		{
			binary = api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
					.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
					.createBinary(in, mediaType, securityContext);
			return new IdType(api.getFhirWebserviceClientProvider().getLocalWebserviceClient().getBaseUrl(),
					ResourceType.Binary.name(), binary.getIdElement().getIdPart(),
					binary.getIdElement().getVersionIdPart()).getValue();
		}
		catch (Exception exception)
		{
			logger.warn("Could not create binary - {}", exception.getMessage());
			throw new RuntimeException("Could not create binary - " + exception.getMessage(), exception);
		}
	}

	private DocumentReference updateDocumentReference(DocumentReference documentReference, List<String> binaryIds)
	{
		documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);

		// Remove dummy attachment created before.
		documentReference.setContent(null);

		binaryIds.forEach(id -> documentReference.addContent().getAttachment().setUrl(id)
				.setContentType(MediaType.APPLICATION_OCTET_STREAM));
		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient()
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES, ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN)
				.update(documentReference);
	}

	private void log(String projectIdentifier, String dmsIdentifier, String documentReferenceId, String taskId)
	{
		logger.info(
				"Stored DocumentReference with id '{}' provided for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				documentReferenceId, dmsIdentifier, projectIdentifier, taskId);
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

	private String getDsfFhirStoreAbsoluteId(IdType idType)
	{
		return new IdType(api.getFhirWebserviceClientProvider().getLocalWebserviceClient().getBaseUrl(),
				idType.getResourceType(), idType.getIdPart(), idType.getVersionIdPart()).getValue();
	}
}
