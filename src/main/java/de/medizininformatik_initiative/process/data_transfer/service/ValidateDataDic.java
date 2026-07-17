package de.medizininformatik_initiative.process.data_transfer.service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.List;

import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.MimeTypeHelper;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.service.MimeTypeService;
import dev.dsf.bpe.v2.variables.Variables;
import jakarta.ws.rs.core.MediaType;

public class ValidateDataDic implements ServiceTask
{
	private static final Logger logger = LoggerFactory.getLogger(ValidateDataDic.class);

	private final String fhirStoreId;
	private final boolean fhirBinaryStreamReadUseHapiBlobStorageOperation;

	public ValidateDataDic(String fhirStoreId, boolean fhirBinaryStreamReadUseHapiBlobStorageOperation)
	{
		this.fhirStoreId = fhirStoreId;
		this.fhirBinaryStreamReadUseHapiBlobStorageOperation = fhirBinaryStreamReadUseHapiBlobStorageOperation;
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables)
	{
		Task task = variables.getStartTask();
		String projectIdentifier = variables
				.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER);
		String dmsIdentifier = variables.getString(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER);

		DsfClient client = getDsfClientForFhirStore(api.getDsfClientProvider(), fhirStoreId);

		logger.info("Validating data-set for DMS '{}' and project-identifier '{}' in Task '{}'", dmsIdentifier,
				projectIdentifier, api.getTaskHelper().getLocalVersionlessAbsoluteUrl(task));

		List<Resource> resources = variables
				.getFhirResourceList(ConstantsDataTransfer.BPMN_EXECUTION_VARIABLE_INITIAL_DATA_RESOURCES);
		resources.forEach(r -> validate(client, api.getFhirContext(), api.getMimeTypeService(), r));
	}

	private DsfClient getDsfClientForFhirStore(DsfClientProvider provider, String fhirStoreId)
	{
		return provider.getById(fhirStoreId)
				.orElseThrow(() -> new RuntimeException("DSF FHIR client '" + fhirStoreId + "' not configured"));
	}

	private void validate(DsfClient client, FhirContext fhirContext, MimeTypeService mimeTypeService, Resource resource)
	{
		if (resource instanceof ListResource list)
			validateStream(client, mimeTypeService, list);
		else
			validateResource(fhirContext, mimeTypeService, resource);
	}

	private void validateResource(FhirContext fhirContext, MimeTypeService mimeTypeService, Resource resource)
	{
		String mimeType = MimeTypeHelper.getMimeType(resource);
		byte[] data = MimeTypeHelper.getData(fhirContext, resource);

		mimeTypeService.validateWithException(data, mimeType);
	}

	private void validateStream(DsfClient client, MimeTypeService mimeTypeService, ListResource list)
	{
		list.getEntry().stream().filter(ListResource.ListEntryComponent::hasItem)
				.filter(e -> e.hasExtension(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE))
				.forEach(e -> doValidateStream(client, mimeTypeService, e));
	}

	private void doValidateStream(DsfClient client, MimeTypeService mimeTypeService,
			ListResource.ListEntryComponent listEntry)
	{
		String binaryId = listEntry.getItem().getReferenceElement().getIdPart();
		if (fhirBinaryStreamReadUseHapiBlobStorageOperation)
			binaryId += "/$binary-access-read";
		String mimetype = listEntry.getExtensionString(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE);

		InputStream inputStream = client.readBinary(binaryId, MediaType.valueOf(mimetype));

		if (!inputStream.markSupported())
			inputStream = new BufferedInputStream(inputStream);

		mimeTypeService.validateWithException(inputStream, mimetype);
	}
}
