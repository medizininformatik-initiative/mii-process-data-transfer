package de.medizininformatik_initiative.process.data_transfer.spring.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import de.medizininformatik_initiative.process.data_transfer.DataTransferProcessPluginDeploymentListener;
import de.medizininformatik_initiative.process.data_transfer.authorization.AuthorizationProvider;
import de.medizininformatik_initiative.process.data_transfer.message.SendData;
import de.medizininformatik_initiative.process.data_transfer.message.SendReceipt;
import de.medizininformatik_initiative.process.data_transfer.service.DecryptValidateAndInsertData;
import de.medizininformatik_initiative.process.data_transfer.service.DeleteData;
import de.medizininformatik_initiative.process.data_transfer.service.DownloadData;
import de.medizininformatik_initiative.process.data_transfer.service.EncryptAndStoreData;
import de.medizininformatik_initiative.process.data_transfer.service.HandleErrorReceive;
import de.medizininformatik_initiative.process.data_transfer.service.HandleErrorSend;
import de.medizininformatik_initiative.process.data_transfer.service.ReadData;
import de.medizininformatik_initiative.process.data_transfer.service.SelectTargetDic;
import de.medizininformatik_initiative.process.data_transfer.service.StoreReceipt;
import de.medizininformatik_initiative.process.data_transfer.service.ValidateDataDic;
import de.medizininformatik_initiative.processes.common.crypto.KeyProvider;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.ProcessPluginDeploymentListener;
import dev.dsf.bpe.v2.documentation.ProcessDocumentation;

@Configuration
public class TransferDataConfig
{
	@Autowired
	private ProcessPluginApi api;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "The ID of a DIC FHIR server from the main DSF configuration as 'DSF FHIR Client'", example = "dic-fhir-store")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.fhir.server.id:#{null}}")
	private String fhirStoreIdDic;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "To enable stream processing when reading Binary resources set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.fhir.server.binary.stream.read.enabled:false}")
	private boolean fhirBinaryStreamReadEnabled;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "If the DIC FHIR server is a HAPI FHIR server and uses external storage for Binary resources via the ENV variable `HAPI_FHIR_BINARY_STORAGE_ENABLED`, set this ENV variable as well to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.fhir.server.binary.stream.read.use.hapi.blob.storage.operation:false}")
	private boolean fhirBinaryStreamReadUseHapiBlobStorageOperation;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "To receive e-mails as DIC, set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.email.enabled:false}")
	private boolean dicEmailEnabled;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "The period the process waits to receive the status from the DMS, must be an ISO 8601 time duration pattern")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.status.timer.interval:PT45M}")
	private String statusTimerInterval;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The ID of a DIC FHIR server from the main DSF configuration as 'DSF FHIR Client'", example = "dms-fhir-store")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.id:#{null}}")
	private String fhirStoreIdDms;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "To enable stream processing when writing Binary resources set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.binary.stream.write.enabled:false}")
	private boolean fhirBinaryStreamWriteEnabled;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "To receive e-mails as DMS, set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.email.enabled:false}")
	private boolean dmsEmailEnabled;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Location of the DMS private-key as 4096 Bit RSA PEM encoded, not encrypted file", recommendation = "Use docker secret file to configure", example = "/run/secrets/dms_private_key.pem")
	@Value("${de.medizininformatik.initiative.dms.private.key:#{null}}")
	private String dmsPrivateKeyFile;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Location of the DMS public-key as 4096 Bit RSA PEM encoded file", recommendation = "Use docker secret file to configure", example = "/run/secrets/dms_public_key.pem")
	@Value("${de.medizininformatik.initiative.dms.public.key:#{null}}")
	private String dmsPublicKeyFile;

	@ProcessDocumentation(required = true, processNames = { "medizininformatik-initiativede_dataSend",
			"medizininformatik-initiativede_dataReceive" }, description = "Adds additional allowed data-set senders to the authorization rules based on the `<consortium-identifier>|<role> definition", recommendation = "If this env variable is set, 'DE_MEDIZININFORMATIK_INITIATIVE_DATA_TRANSFER_PROCESS_AUTHORIZATION_ADDITIONALLY_ALLOWED_RECEIVERS' should be set as well", example = "nct.dkfz.de|DIC")
	@Value("#{'${de.medizininformatik.initiative.data.transfer.process.authorization.additionally.allowed.senders:medizininformatik-initiative.de|DIC}'.trim().split('(,[ ]?)|(\\n)')}")
	private List<String> additionallyAllowedSenders;

	@ProcessDocumentation(required = true, processNames = { "medizininformatik-initiativede_dataSend",
			"medizininformatik-initiativede_dataReceive" }, description = "Adds additional allowed data-set receivers to the authorization rules based on the `<consortium-identifier>|<role> definition", recommendation = "If this env variable is set, 'DE_MEDIZININFORMATIK_INITIATIVE_DATA_TRANSFER_PROCESS_AUTHORIZATION_ADDITIONALLY_ALLOWED_SENDERS' should be set as well", example = "nct.dkfz.de|DMS")
	@Value("#{'${de.medizininformatik.initiative.data.transfer.process.authorization.additionally.allowed.receivers:medizininformatik-initiative.de|DMS}'.trim().split('(,[ ]?)|(\\n)')}")
	private List<String> additionallyAllowedReceivers;

	// all Processes

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public KeyProvider keyProviderDic()
	{
		return KeyProvider.from(api);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public KeyProvider keyProviderDms()
	{
		return KeyProvider.from(api, dmsPrivateKeyFile, dmsPublicKeyFile);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DataSetStatusGenerator dataSetStatusGenerator()
	{
		return new DataSetStatusGenerator();
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
	public AuthorizationProvider authorizationProvider()
	{
		return new AuthorizationProvider(api, additionallyAllowedSenders, additionallyAllowedReceivers);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
	public ProcessPluginDeploymentListener dataTransferProcessPluginDeploymentListener()
	{
		return new DataTransferProcessPluginDeploymentListener(api, fhirStoreIdDic, fhirStoreIdDms, keyProviderDms(),
				authorizationProvider());
	}

	// dataSend

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ReadData readData()
	{
		return new ReadData(fhirStoreIdDic, fhirBinaryStreamReadEnabled, statusTimerInterval);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ValidateDataDic validateDataDic()
	{
		return new ValidateDataDic(fhirStoreIdDic, fhirBinaryStreamReadUseHapiBlobStorageOperation);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public EncryptAndStoreData encryptAndStoreData()
	{
		return new EncryptAndStoreData(fhirStoreIdDic, fhirBinaryStreamReadUseHapiBlobStorageOperation,
				dataSetStatusGenerator(), keyProviderDic(), dicEmailEnabled);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SendData sendData()
	{
		return new SendData();
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public HandleErrorSend handleErrorSend()
	{
		return new HandleErrorSend(dataSetStatusGenerator(), dicEmailEnabled);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public StoreReceipt storeReceipt()
	{
		return new StoreReceipt(dataSetStatusGenerator(), dicEmailEnabled);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DeleteData deleteData()
	{
		return new DeleteData();
	}

	// dataReceive

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DownloadData downloadData()
	{
		return new DownloadData(fhirBinaryStreamWriteEnabled, dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DecryptValidateAndInsertData decryptValidateAndInsertData()
	{
		return new DecryptValidateAndInsertData(fhirStoreIdDms, keyProviderDms(), dataSetStatusGenerator(),
				dmsEmailEnabled);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public HandleErrorReceive handleErrorReceive()
	{
		return new HandleErrorReceive(dataSetStatusGenerator(), dmsEmailEnabled);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SelectTargetDic selectTargetDic()
	{
		return new SelectTargetDic();
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SendReceipt sendReceipt()
	{
		return new SendReceipt(api, dataSetStatusGenerator());
	}
}
