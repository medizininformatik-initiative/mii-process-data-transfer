package de.medizininformatik_initiative.process.data_transfer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import de.medizininformatik_initiative.process.data_transfer.spring.config.DicFhirClientConfig;
import de.medizininformatik_initiative.process.data_transfer.spring.config.DmsFhirClientConfig;
import de.medizininformatik_initiative.process.data_transfer.spring.config.TransferDataConfig;
import dev.dsf.bpe.v1.ProcessPluginDefinition;

public class DataTransferProcessPluginDefinition implements ProcessPluginDefinition
{
	public static final String VERSION = "1.0.0.0";
	public static final LocalDate RELEASE_DATE = LocalDate.of(2022, 8, 15);

	@Override
	public String getName()
	{
		return "mii-process-data-transfer";
	}

	@Override
	public String getVersion()
	{
		return VERSION;
	}

	@Override
	public LocalDate getReleaseDate()
	{
		return RELEASE_DATE;
	}

	@Override
	public List<String> getProcessModels()
	{
		return List.of("bpe/send.bpmn", "bpe/receive.bpmn");
	}

	@Override
	public List<Class<?>> getSpringConfigurations()
	{
		return List.of(TransferDataConfig.class, DicFhirClientConfig.class, DmsFhirClientConfig.class);
	}

	@Override
	public Map<String, List<String>> getFhirResourcesByProcessId()
	{
		var aReceive = "fhir/ActivityDefinition/data-transfer-receive.xml";
		var aSend = "fhir/ActivityDefinition/data-transfer-send.xml";

		var cCrypto = "fhir/CodeSystem/mii-cryptography.xml";
		var cDaSeSt = "fhir/CodeSystem/data-set-status.xml";
		var cDaTr = "fhir/CodeSystem/data-transfer.xml";

		var eDaSeStEr = "fhir/StructureDefinition/extension-data-set-status-error.xml";

		var nPrId = "fhir/NamingSystem/mii-project-identifier.xml";

		var sReceive = "fhir/StructureDefinition/task-data-receive.xml";
		var sSend = "fhir/StructureDefinition/task-data-send.xml";
		var sSendStart = "fhir/StructureDefinition/task-data-send-start.xml";

		var tSendStart = "fhir/Task/task-data-send-start.xml";

		var vCrypto = "fhir/ValueSet/mii-cryptography.xml";
		var vDaSeStRe = "fhir/ValueSet/data-set-status-receive.xml";
		var vDaSeStSe = "fhir/ValueSet/data-set-status-send.xml";
		var vDaTr = "fhir/ValueSet/data-transfer.xml";

		return Map.of( //
				ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE, //
				Arrays.asList(aReceive, cCrypto, cDaSeSt, cDaTr, eDaSeStEr, nPrId, sSend, vCrypto, vDaSeStRe, vDaTr), //
				ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND, //
				Arrays.asList(aSend, cDaSeSt, cDaTr, eDaSeStEr, nPrId, sSendStart, sReceive, tSendStart, vDaSeStSe,
						vDaTr));
	}
}
