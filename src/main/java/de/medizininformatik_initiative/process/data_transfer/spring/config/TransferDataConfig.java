package de.medizininformatik_initiative.process.data_transfer.spring.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import de.medizininformatik_initiative.process.data_transfer.DataTransferProcessPluginDeploymentStateListener;
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
import de.medizininformatik_initiative.processes.common.crypto.KeyProviderImpl;
import de.medizininformatik_initiative.processes.common.mimetype.CombinedDetectors;
import de.medizininformatik_initiative.processes.common.mimetype.MimeTypeHelper;
import de.medizininformatik_initiative.processes.common.util.DataSetStatusGenerator;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.ProcessPluginDeploymentStateListener;
import dev.dsf.bpe.v1.documentation.ProcessDocumentation;

@Configuration
public class TransferDataConfig
{
	@Autowired
	private ProcessPluginApi api;

	@Autowired
	private DicFhirClientConfig dicFhirClientConfig;

	@Autowired
	private DmsFhirClientConfig dmsFhirClientConfig;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataSend" }, description = "To enable stream processing when reading Binary resources set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dic.fhir.server.binary.stream.read.enabled:false}")
	private boolean fhirBinaryStreamReadEnabled;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "To enable stream processing when writing Binary resources set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.binary.stream.write.enabled:false}")
	private boolean fhirBinaryStreamWriteEnabled;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Location of the DMS private-key as 4096 Bit RSA PEM encoded, not encrypted file", recommendation = "Use docker secret file to configure", example = "/run/secrets/dms_private_key.pem")
	@Value("${de.medizininformatik.initiative.dms.private.key:#{null}}")
	private String dmsPrivateKeyFile;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Location of the DMS public-key as 4096 Bit RSA PEM encoded file", recommendation = "Use docker secret file to configure", example = "/run/secrets/dms_public_key.pem")
	@Value("${de.medizininformatik.initiative.dms.public.key:#{null}}")
	private String dmsPublicKeyFile;

	// all Processes

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public MimeTypeHelper mimeTypeHelper()
	{
		return new MimeTypeHelper(CombinedDetectors.fromDefaultWithNdJson(), api.getFhirContext());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public KeyProvider keyProviderDms()
	{
		return KeyProviderImpl.fromFiles(api, dmsPrivateKeyFile, dmsPublicKeyFile, dmsFhirClientConfig.dataLogger());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public KeyProvider keyProviderDic()
	{
		return KeyProviderImpl.fromFiles(api, null, null, dicFhirClientConfig.dataLogger());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DataSetStatusGenerator dataSetStatusGenerator()
	{
		return new DataSetStatusGenerator();
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ProcessPluginDeploymentStateListener dataTransferProcessPluginDeploymentStateListener()
	{
		return new DataTransferProcessPluginDeploymentStateListener(dicFhirClientConfig.fhirClientFactory(),
				dmsFhirClientConfig.fhirClientFactory(), keyProviderDms());
	}

	// dataSend

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ReadData readData()
	{
		return new ReadData(api, dicFhirClientConfig.fhirClientFactory(), fhirBinaryStreamReadEnabled,
				dicFhirClientConfig.dataLogger());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ValidateDataDic validateDataDic()
	{
		return new ValidateDataDic(api, mimeTypeHelper(), dicFhirClientConfig.fhirClientFactory());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public EncryptAndStoreData encryptAndStoreData()
	{
		return new EncryptAndStoreData(api, keyProviderDic(), dicFhirClientConfig.fhirClientFactory());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SendData sendData()
	{
		return new SendData(api, dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public HandleErrorSend handleErrorSend()
	{
		return new HandleErrorSend(api);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public StoreReceipt storeReceipt()
	{
		return new StoreReceipt(api, dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DeleteData deleteData()
	{
		return new DeleteData(api);
	}

	// dataReceive

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DownloadData downloadData()
	{
		return new DownloadData(api, dataSetStatusGenerator(), fhirBinaryStreamWriteEnabled,
				dmsFhirClientConfig.dataLogger());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DecryptValidateAndInsertData decryptValidateAndInsertData()
	{
		return new DecryptValidateAndInsertData(api, keyProviderDms(), mimeTypeHelper(),
				dmsFhirClientConfig.fhirClientFactory(), dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public HandleErrorReceive handleErrorReceive()
	{
		return new HandleErrorReceive(api);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SelectTargetDic selectTargetDic()
	{
		return new SelectTargetDic(api);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SendReceipt sendReceipt()
	{
		return new SendReceipt(api, dataSetStatusGenerator());
	}
}
