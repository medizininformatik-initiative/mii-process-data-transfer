package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Binary;
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
import de.medizininformatik_initiative.process.data_transfer.variables.DataResource;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.BasicDsfClient;
import dev.dsf.bpe.v2.client.dsf.DelayStrategy;
import dev.dsf.bpe.v2.error.ErrorBoundaryEvent;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class DownloadData implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(DownloadData.class);

	private final String fhirStoreId;
	private final boolean fhirBinaryStreamWriteEnabled;
	private final DataSetStatusGenerator statusGenerator;

	public DownloadData(String fhirStoreId, boolean fhirBinaryStreamWriteEnabled,
			DataSetStatusGenerator statusGenerator)
	{
		this.fhirStoreId = fhirStoreId;
		this.fhirBinaryStreamWriteEnabled = fhirBinaryStreamWriteEnabled;
		this.statusGenerator = statusGenerator;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(statusGenerator, "statusGenerator");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws ErrorBoundaryEvent, Exception
	{
		Task task = variables.getStartTask();
		String sendingOrganization = task.getRequester().getIdentifier().getValue();

		String consortiumIdentifier = getConsortiumIdentifier(api, task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_CONSORTIUM_IDENTIFIER, consortiumIdentifier);

		String projectIdentifier = getProjectIdentifier(task);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);

		IdType documentReferenceLocation = getDocumentReferenceLocation(api, task, sendingOrganization,
				projectIdentifier);
		variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION,
				documentReferenceLocation.getValue());

		logger.info(
				"Downloading data-set from organization '{}' for project-identifier '{}' referenced in Task with id '{}' (DocumentReference with id '{}' and its encrypted attachments)",
				sendingOrganization, projectIdentifier, task.getId(), documentReferenceLocation.getValue());

		try
		{
			DocumentReference documentReference = readDocumentReference(api, documentReferenceLocation,
					sendingOrganization, projectIdentifier, task.getId());
			Stream<DataResource> attachments = readAttachments(api, documentReference);
			List<Resource> resources = getResources(api, attachments, sendingOrganization, projectIdentifier,
					task.getId());

			variables.setString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER, projectIdentifier);
			variables.setFhirResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE,
					documentReference);
			variables.setFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_TRANSFER_DATA_RESOURCES,
					resources);
		}
		catch (Exception exception)
		{
			String error = "Download data-set failed - " + exception.getMessage();
			throw new ErrorBoundaryEvent(ConstantsBase.CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR, error);
		}
	}

	private String getProjectIdentifier(Task task)
	{
		return task.getInput().stream().filter(i -> i.getType().getCoding().stream()
				.anyMatch(c -> ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER.equals(c.getCode())))
				.filter(i -> i.getValue() instanceof Identifier).map(i -> (Identifier) i.getValue())
				.filter(i -> ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER.equals(i.getSystem()))
				.map(Identifier::getValue).findFirst()
				.orElseThrow(() -> new RuntimeException("No project-identifier present in Task.input"));
	}

	private String getConsortiumIdentifier(ProcessPluginApi api, Task task)
	{
		return api.getTaskHelper()
				.getFirstInputParameterValue(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_CONSORTIUM_IDENTIFIER, Reference.class)
				.orElseThrow(() -> new IllegalArgumentException("No consortium identifier present in Task.input"))
				.getIdentifier().getValue();
	}

	private IdType getDocumentReferenceLocation(ProcessPluginApi api, Task task, String sendingOrganization,
			String projectIdentifier)
	{
		List<String> dataSetReferences = api.getTaskHelper()
				.getInputParameters(task, ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER,
						ConstantsDataTransfer.CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION,
						Reference.class)
				.map(Task.ParameterComponent::getValue).filter(i -> i instanceof Reference).map(i -> (Reference) i)
				.filter(Reference::hasReference).map(Reference::getReference).toList();

		if (dataSetReferences.isEmpty())
			throw new IllegalArgumentException("No DocumentReference reference present in Task.input");

		if (dataSetReferences.size() > 1)
			logger.warn(
					"Found {} DocumentReference references from organization '{}' for project-identifier '{}' referenced in Task with id '{}', using only the first",
					dataSetReferences.size(), sendingOrganization, projectIdentifier, task.getId());

		return new IdType(dataSetReferences.getFirst());
	}

	private DocumentReference readDocumentReference(ProcessPluginApi api, IdType documentReferenceLocation,
			String sendingOrganization, String projectIdentifier, String taskId)
	{
		DocumentReference documentReference = api.getDsfClientProvider()
				.getByEndpointUrl(documentReferenceLocation.getBaseUrl())
				.withRetry(ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
						DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN))
				.read(DocumentReference.class, documentReferenceLocation.getIdPart(),
						documentReferenceLocation.getVersionIdPart());

		api.getDataLogger().log("DocumentReference from organization '" + sendingOrganization
				+ "' for project-identifier '" + projectIdentifier + "' referenced in Task with id '" + taskId + "'",
				documentReference);

		return documentReference;
	}

	private Stream<DataResource> readAttachments(ProcessPluginApi api, DocumentReference documentReference)
	{
		return documentReference.getContent().stream()
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment)
				.map(a -> readAttachment(api, a));
	}

	private DataResource readAttachment(ProcessPluginApi api, Attachment attachment)
	{
		IdType attachmentId = new IdType(attachment.getUrl());

		BasicDsfClient client = api.getDsfClientProvider().getByEndpointUrl(attachmentId.getBaseUrl()).withRetry(
				ConstantsBase.DSF_CLIENT_RETRY_6_TIMES,
				DelayStrategy.constant(ConstantsBase.DSF_CLIENT_RETRY_INTERVAL_5MIN));

		String mimetype = getAttachmentMimeType(attachment);
		if (fhirBinaryStreamWriteEnabled && !isMimetypeFhir(mimetype))
		{
			return DataResource.of(attachmentId, mimetype);
		}
		else
		{
			try (InputStream binary = readBinaryResource(client, attachmentId.getIdPart(),
					attachmentId.getVersionIdPart()))
			{
				return DataResource
						.of(new Binary().setData(binary.readAllBytes()).setContentType(attachment.getContentType()));
			}
			catch (Exception exception)
			{
				throw new RuntimeException("Downloading attachment failed - " + exception.getMessage(), exception);
			}
		}
	}

	private String getAttachmentMimeType(Attachment attachment)
	{
		return Optional.of(attachment).filter(Attachment::hasContentType).map(Attachment::getContentType)
				.orElseThrow(() -> new IllegalArgumentException(
						"Could not find any attachment contentType (mimeType) in DocumentReference"));
	}

	private InputStream readBinaryResource(BasicDsfClient client, String id, String version)
	{
		MediaType mediaType = MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM);
		if (version != null && !version.isEmpty())
			return client.readBinary(id, version, mediaType);
		else
			return client.readBinary(id, mediaType);
	}

	private List<Resource> getResources(ProcessPluginApi api, Stream<DataResource> dataResources,
			String sendingOrganization, String projectIdentifier, String taskId)
	{
		return dataResources.map(DataResource::toResource).filter(Objects::nonNull)
				.peek(r -> api.getDataLogger()
						.log("Read attachment from organization '" + sendingOrganization + "' for project-identifier '"
								+ projectIdentifier + "' referenced in Task with id '" + taskId + "'", r))
				.toList();
	}

	private boolean isMimetypeFhir(String mimetype)
	{
		return "application/fhir+xml".equals(mimetype) || "application/fhir+json".equals(mimetype);
	}
}
