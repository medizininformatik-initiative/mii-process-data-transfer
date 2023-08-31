package de.medizininformatik_initiative.process.data_transfer.service;

import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.FINAL;
import static org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.activity.AbstractServiceDelegate;
import dev.dsf.bpe.v1.variables.Variables;

public class CreateBundle extends AbstractServiceDelegate implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(CreateBundle.class);

	private final DataLogger dataLogger;

	public CreateBundle(ProcessPluginApi api, DataLogger dataLogger)
	{
		super(api);
		this.dataLogger = dataLogger;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		super.afterPropertiesSet();
		Objects.requireNonNull(dataLogger, "dataLogger");
	}

	@Override
	protected void doExecute(DelegateExecution execution, Variables variables)
	{
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		logger.info(
				"Creating transferable data-set for DMS '{}' and project-identifier '{}' referenced in Task with id '{}'",
				dmsIdentifier, projectIdentifier, variables.getStartTask().getId());

		DocumentReference documentReference = variables
				.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DOCUMENT_REFERENCE);
		Resource resource = variables.getResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_RESOURCE);
		Bundle bundle = createTransactionBundle(variables, projectIdentifier, documentReference, resource);

		dataLogger.logResource("Created Transfer Bundle", bundle);

		variables.setResource(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DATA_SET, bundle);
	}

	private Bundle createTransactionBundle(Variables variables, String projectIdentifier,
			DocumentReference documentReference, Resource resource)
	{
		Resource attachmentToTransmit = resource.setId(UUID.randomUUID().toString());

		DocumentReference documentReferenceToTransmit = new DocumentReference().setStatus(CURRENT).setDocStatus(FINAL);
		documentReferenceToTransmit.setId(UUID.randomUUID().toString());
		documentReferenceToTransmit.getMasterIdentifier().setSystem(ConstantsBase.NAMINGSYSTEM_MII_PROJECT_IDENTIFIER)
				.setValue(projectIdentifier);
		documentReferenceToTransmit.addAuthor().setType(ResourceType.Organization.name())
				.setIdentifier(api.getOrganizationProvider().getLocalOrganizationIdentifier()
						.orElseThrow(() -> new RuntimeException("LocalOrganizationIdentifier is null")));
		documentReferenceToTransmit.setDate(documentReference.getDate());

		String contentType = getFirstAttachmentContentType(variables, documentReference, projectIdentifier);
		documentReferenceToTransmit.addContent().getAttachment().setContentType(contentType)
				.setUrl("urn:uuid:" + resource.getId());

		Bundle bundle = new Bundle().setType(TRANSACTION);
		bundle.addEntry().setResource(documentReferenceToTransmit)
				.setFullUrl("urn:uuid:" + documentReferenceToTransmit.getId()).getRequest()
				.setMethod(Bundle.HTTPVerb.POST).setUrl(ResourceType.DocumentReference.name());
		bundle.addEntry().setResource(attachmentToTransmit).setFullUrl("urn:uuid:" + attachmentToTransmit.getId())
				.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl(attachmentToTransmit.getResourceType().name());

		return bundle;
	}

	private String getFirstAttachmentContentType(Variables variables, DocumentReference documentReference,
			String projectIdentifier)
	{
		List<Attachment> attachments = Stream.of(documentReference).filter(DocumentReference::hasContent)
				.flatMap(dr -> dr.getContent().stream())
				.filter(DocumentReference.DocumentReferenceContentComponent::hasAttachment)
				.map(DocumentReference.DocumentReferenceContentComponent::getAttachment).filter(Attachment::hasUrl)
				.toList();

		if (attachments.size() < 1)
			throw new IllegalArgumentException(
					"Could not find any attachment in DocumentReference with masterIdentifier '" + projectIdentifier
							+ "' stored on KDS FHIR server for Task with id '" + variables.getStartTask().getId()
							+ "'");

		return attachments.get(0).getContentType();
	}
}
