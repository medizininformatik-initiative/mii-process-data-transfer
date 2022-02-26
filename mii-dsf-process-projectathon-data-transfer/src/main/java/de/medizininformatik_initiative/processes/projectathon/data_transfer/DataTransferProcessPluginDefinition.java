package de.medizininformatik_initiative.processes.projectathon.data_transfer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.highmed.dsf.bpe.ProcessPluginDefinition;
import org.highmed.dsf.fhir.resources.AbstractResource;
import org.highmed.dsf.fhir.resources.ActivityDefinitionResource;
import org.highmed.dsf.fhir.resources.CodeSystemResource;
import org.highmed.dsf.fhir.resources.NamingSystemResource;
import org.highmed.dsf.fhir.resources.ResourceProvider;
import org.highmed.dsf.fhir.resources.StructureDefinitionResource;
import org.highmed.dsf.fhir.resources.ValueSetResource;
import org.springframework.core.env.PropertyResolver;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.processes.projectathon.data_transfer.spring.config.TransferDataConfig;

public class DataTransferProcessPluginDefinition implements ProcessPluginDefinition
{
	public static final String VERSION = "0.1.0";

	@Override
	public String getName()
	{
		return "mii-process-projectathon-data-transfer";
	}

	@Override
	public String getVersion()
	{
		return VERSION;
	}

	@Override
	public Stream<String> getBpmnFiles()
	{
		return Stream.of("bpe/send.bpmn", "bpe/receive.bpmn");
	}

	@Override
	public Stream<Class<?>> getSpringConfigClasses()
	{
		return Stream.of(TransferDataConfig.class);
	}

	@Override
	public ResourceProvider getResourceProvider(FhirContext fhirContext, ClassLoader classLoader,
			PropertyResolver propertyResolver)
	{
		var aSen = ActivityDefinitionResource.file("fhir/ActivityDefinition/mii-projectathon-data-send.xml");
		var aRec = ActivityDefinitionResource.file("fhir/ActivityDefinition/mii-projectathon-data-receive.xml");

		var cC = CodeSystemResource.file("fhir/CodeSystem/mii-cryptography.xml");
		var cD = CodeSystemResource.file("fhir/CodeSystem/mii-data-transfer.xml");
		var nP = NamingSystemResource.file("fhir/NamingSystem/mii-project-identifier.xml");

		var sTstaDsen = StructureDefinitionResource
				.file("fhir/StructureDefinition/mii-projectathon-task-start-data-send.xml");
		var sTstaDrec = StructureDefinitionResource
				.file("fhir/StructureDefinition/mii-projectathon-task-start-data-receive.xml");

		var vC = ValueSetResource.file("fhir/ValueSet/mii-cryptography.xml");
		var vD = ValueSetResource.file("fhir/ValueSet/mii-data-transfer.xml");

		Map<String, List<AbstractResource>> resourcesByProcessKeyAndVersion = Map.of( //
				"medizininformatik-initiativede_dataSend/" + VERSION,
				Arrays.asList(aSen, cC, cD, nP, sTstaDsen, vC, vD), //
				"medizininformatik-initiativede_dataReceive/" + VERSION,
				Arrays.asList(aRec, cC, cD, nP, sTstaDrec, vC, vD));

		return ResourceProvider.read(VERSION, () -> fhirContext.newXmlParser().setStripVersionsFromReferences(false),
				classLoader, propertyResolver, resourcesByProcessKeyAndVersion);
	}
}