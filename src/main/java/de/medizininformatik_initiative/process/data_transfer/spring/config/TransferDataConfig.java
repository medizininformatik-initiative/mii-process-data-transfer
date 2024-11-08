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
import de.medizininformatik_initiative.process.data_transfer.service.CreateBundle;
import de.medizininformatik_initiative.process.data_transfer.service.DecryptData;
import de.medizininformatik_initiative.process.data_transfer.service.DeleteData;
import de.medizininformatik_initiative.process.data_transfer.service.DownloadData;
import de.medizininformatik_initiative.process.data_transfer.service.EncryptData;
import de.medizininformatik_initiative.process.data_transfer.service.HandleErrorReceive;
import de.medizininformatik_initiative.process.data_transfer.service.HandleErrorSend;
import de.medizininformatik_initiative.process.data_transfer.service.InsertData;
import de.medizininformatik_initiative.process.data_transfer.service.ReadData;
import de.medizininformatik_initiative.process.data_transfer.service.SelectTargetDic;
import de.medizininformatik_initiative.process.data_transfer.service.StoreData;
import de.medizininformatik_initiative.process.data_transfer.service.StoreReceipt;
import de.medizininformatik_initiative.process.data_transfer.service.ValidateDataDic;
import de.medizininformatik_initiative.process.data_transfer.service.ValidateDataDms;
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
		return new ReadData(api, dicFhirClientConfig.fhirClientFactory());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ValidateDataDic validateDataDic()
	{
		return new ValidateDataDic(api, mimeTypeHelper());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public CreateBundle createBundle()
	{
		return new CreateBundle(api, dicFhirClientConfig.dataLogger());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public EncryptData encryptData()
	{
		return new EncryptData(api, keyProviderDic());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public StoreData storeData()
	{
		return new StoreData(api);
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
		return new DownloadData(api, dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public DecryptData decryptData()
	{
		return new DecryptData(api, keyProviderDms(), dmsFhirClientConfig.dataLogger(), dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ValidateDataDms validateDataDms()
	{
		return new ValidateDataDms(api, mimeTypeHelper(), dataSetStatusGenerator());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public InsertData insertData()
	{
		return new InsertData(api, dmsFhirClientConfig.fhirClientFactory(), dataSetStatusGenerator());
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
