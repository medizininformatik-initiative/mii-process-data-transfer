package de.medizininformatik_initiative.process.data_transfer.variables;

import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;

public record DataResource(Resource resource, IdType streamLocation, String mimetype)
{
	public static DataResource of(Resource resource)
	{
		return new DataResource(resource, null, null);
	}

	public static DataResource of(IdType streamLocation, String mimeType)
	{
		return new DataResource(null, streamLocation, mimeType);
	}

	public boolean hasResource()
	{
		return resource != null;
	}

	public boolean hasStreamLocation()
	{
		return streamLocation != null;
	}
}