package de.medizininformatik_initiative.process.data_transfer.spring.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.context.FhirContext;
import de.medizininformatik_initiative.processes.common.fhir.client.FhirClientFactory;
import de.medizininformatik_initiative.processes.common.fhir.client.logging.DataLogger;
import dev.dsf.bpe.v1.documentation.ProcessDocumentation;

@Configuration
public class DmsFhirClientConfig
{
	// TODO: use default proxy config from DSF
	@Autowired
	private FhirContext fhirContext;

	@ProcessDocumentation(required = true, processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The base address of the DMS FHIR server to read/store FHIR resources", example = "http://foo.bar/fhir")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.base.url:#{null}}")
	private String fhirStoreBaseUrl;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "PEM encoded file with one or more trusted root certificate to validate the DMS FHIR server certificate when connecting via https", recommendation = "Use docker secret file to configure", example = "/run/secrets/hospital_ca.pem")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.trust.certificates:#{null}}")
	private String fhirStoreTrustStore;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "PEM encoded file with client-certificate, if DMS FHIR server requires mutual TLS authentication", recommendation = "Use docker secret file to configure", example = "/run/secrets/fhir_server_client_certificate.pem")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.certificate:#{null}}")
	private String fhirStoreCertificate;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Private key corresponding to the DMS FHIR server client-certificate as PEM encoded file. Use *${env_variable}_PASSWORD* or *${env_variable}_PASSWORD_FILE* if private key is encrypted", recommendation = "Use docker secret file to configure", example = "/run/secrets/fhir_server_private_key.pem")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.private.key:#{null}}")
	private String fhirStorePrivateKey;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Password to decrypt the DMS FHIR server client-certificate encrypted private key", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*", example = "/run/secrets/fhir_server_private_key.pem.password")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.private.key.password:#{null}}")
	private char[] fhirStorePrivateKeyPassword;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Basic authentication username, set if the server containing the FHIR data requests authentication using basic auth")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.basicauth.username:#{null}}")
	private String fhirStoreUsername;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Basic authentication password, set if the server containing the FHIR data requests authentication using basic auth", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*", example = "/run/secrets/fhir_server_basicauth.password")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.basicauth.password:#{null}}")
	private String fhirStorePassword;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Bearer token for authentication, set if the server containing the FHIR data requests authentication using a bearer token, cannot be set using docker secrets")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.bearer.token:#{null}}")
	private String fhirStoreBearerToken;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The timeout in milliseconds until a connection is established between the client and the DMS FHIR server", recommendation = "Change default value only if timeout exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.timeout.connect:20000}")
	private int fhirStoreConnectTimeout;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The timeout in milliseconds used when requesting a connection from the connection manager between the client and the DMS FHIR server", recommendation = "Change default value only if timeout exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.timeout.connection.request:20000}")
	private int fhirStoreConnectionRequestTimeout;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Maximum period of inactivity in milliseconds between two consecutive data packets of the client and the DMS FHIR server", recommendation = "Change default value only if timeout exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.timeout.socket:60000}")
	private int fhirStoreSocketTimeout;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The client will log additional debug output", recommendation = "Change default value only if exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.client.verbose:false}")
	private boolean fhirStoreHapiClientVerbose;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy location, set if the server containing the FHIR data can only be reached through a proxy", example = "http://proxy.foo:8080")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.url:#{null}}")
	private String fhirStoreProxyUrl;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy username, set if the server containing the FHIR data can only be reached through a proxy which requests authentication")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.username:#{null}}")
	private String fhirStoreProxyUsername;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy password, set if the server containing the FHIR data can only be reached through a proxy which requests authentication", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.password:#{null}}")
	private String fhirStoreProxyPassword;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "To enable debug logging of FHIR resources set to `true`")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.dataLoggingEnabled:false}")
	private boolean fhirDataLoggingEnabled;

	@Value("${dev.dsf.bpe.fhir.server.organization.identifier.value}")
	private String localIdentifierValue;

	public FhirClientFactory fhirClientFactory()
	{
		Path trustStorePath = checkExists(fhirStoreTrustStore);
		Path certificatePath = checkExists(fhirStoreCertificate);
		Path privateKeyPath = checkExists(fhirStorePrivateKey);

		return new FhirClientFactory(trustStorePath, certificatePath, privateKeyPath, fhirStorePrivateKeyPassword,
				fhirStoreConnectTimeout, fhirStoreSocketTimeout, fhirStoreConnectionRequestTimeout, fhirStoreBaseUrl,
				fhirStoreUsername, fhirStorePassword, fhirStoreBearerToken, fhirStoreProxyUrl, fhirStoreProxyUsername,
				fhirStoreProxyPassword, fhirStoreHapiClientVerbose, fhirContext, localIdentifierValue, dataLogger());
	}

	public DataLogger dataLogger()
	{
		return new DataLogger(fhirDataLoggingEnabled, fhirContext);
	}

	private Path checkExists(String file)
	{
		if (file == null)
			return null;
		else
		{
			Path path = Paths.get(file);

			if (!Files.isReadable(path))
				throw new RuntimeException(path.toString() + " not readable");

			return path;
		}
	}
}
