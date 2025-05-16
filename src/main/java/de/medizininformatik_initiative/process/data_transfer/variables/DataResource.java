package de.medizininformatik_initiative.process.data_transfer.variables;

import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;

@FunctionalInterface
public interface DataResource
{
	Resource toResource();

	static DataResource of(Resource resource)
	{
		return () -> resource;
	}

	static DataResource of(IdType streamLocation, String mimeType)
	{
		return () ->
		{
			ListResource.ListEntryComponent entry = new ListResource.ListEntryComponent();

			entry.getItem().setReferenceElement(streamLocation);
			entry.addExtension().setUrl(ConstantsDataTransfer.EXTENSION_LIST_ENTRY_MIMETYPE)
					.setValue(new StringType(mimeType));

			return new ListResource().addEntry(entry);
		};
	}
}