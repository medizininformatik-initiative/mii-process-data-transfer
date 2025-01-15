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
import de.medizininformatik_initiative.processes.common.fhir.client.token.OAuth2TokenClient;
import de.medizininformatik_initiative.processes.common.fhir.client.token.OAuth2TokenProvider;
import de.medizininformatik_initiative.processes.common.fhir.client.token.TokenClient;
import de.medizininformatik_initiative.processes.common.fhir.client.token.TokenProvider;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.bpe.v1.documentation.ProcessDocumentation;

@Configuration
public class DmsFhirClientConfig
{
	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private ProcessPluginApi api;

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
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy location, set if the server containing the FHIR data can only be reached through a proxy, uses value from DEV_DSF_PROXY_URL if not set", example = "http://proxy.foo:8080")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.url:#{null}}")
	private String fhirStoreProxyUrl;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy username, set if the server containing the FHIR data can only be reached through a proxy which requests authentication, uses value from DEV_DSF_PROXY_USERNAME if not set")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.username:#{null}}")
	private String fhirStoreProxyUsername;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy password, set if the server containing the FHIR data can only be reached through a proxy which requests authentication, uses value from DEV_DSF_PROXY_PASSWORD if not set", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.proxy.password:#{null}}")
	private String fhirStoreProxyPassword;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The base url of the oidc provider", example = "http://foo.baz/realms/fhir-realm")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.issuer.url:#{null}}")
	private String fhirStoreOAuth2IssuerUrl;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The path for oidc discovery protocol", recommendation = "Change default value only if path is differs from the oidc specification")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.discovery.path:/.well-known/openid-configuration}")
	private String fhirStoreOAuth2DiscoveryPath;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Identifier of the client (username) used for authentication when accessing the oidc provider token endpoint")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.client.id:#{null}}")
	private String fhirStoreOAuth2ClientId;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Secret of the client (password) used for authentication when accessing the oidc provider token endpoint", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.client.password:#{null}}")
	private String fhirStoreOAuth2ClientSecret;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "The timeout in milliseconds until a connection is established between the client and the oidc provider", recommendation = "Change default value only if timeout exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.timeout.connect:20000}")
	private int fhirStoreOAuth2ConnectTimeout;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Maximum period of inactivity in milliseconds between two consecutive data packets of the client and the oidc provider", recommendation = "Change default value only if timeout exceptions occur")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.timeout.socket:60000}")
	private int fhirStoreOAuth2SocketTimeout;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "PEM encoded file with one or more trusted root certificate to validate the oidc provider server certificate when connecting via https", recommendation = "Use docker secret file to configure", example = "/run/secrets/hospital_ca.pem")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.trust.certificates:#{null}}")
	private String fhirStoreOAuth2TrustStore;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy location, set if the oidc provider can only be reached through a proxy, uses value from DEV_DSF_PROXY_URL if not set", example = "http://proxy.foo:8080")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.proxy.url:#{null}}")
	private String fhirStoreOAuth2ProxyUrl;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy username, set if the oidc provider can only be reached through a proxy which requests authentication, uses value from DEV_DSF_PROXY_USERNAME if not set")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.proxy.username:#{null}}")
	private String fhirStoreOAuth2ProxyUsername;

	@ProcessDocumentation(processNames = {
			"medizininformatik-initiativede_dataReceive" }, description = "Proxy password, set if the oidc provider can only be reached through a proxy which requests authentication, uses value from DEV_DSF_PROXY_PASSWORD if not set", recommendation = "Use docker secret file to configure by using *${env_variable}_FILE*")
	@Value("${de.medizininformatik.initiative.data.transfer.dms.fhir.server.oauth2.proxy.password:#{null}}")
	private String fhirStoreOAuth2ProxyPassword;

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

		String proxyUrl = fhirStoreProxyUrl, proxyUsername = fhirStoreProxyUsername,
				proxyPassword = fhirStoreProxyPassword;
		if (proxyUrl == null && api.getProxyConfig().isEnabled()
				&& !api.getProxyConfig().isNoProxyUrl(fhirStoreBaseUrl))
		{
			proxyUrl = api.getProxyConfig().getUrl();
			proxyUsername = api.getProxyConfig().getUsername();
			proxyPassword = api.getProxyConfig().getPassword() == null ? null
					: new String(api.getProxyConfig().getPassword());
		}

		return new FhirClientFactory(trustStorePath, certificatePath, privateKeyPath, fhirStorePrivateKeyPassword,
				fhirStoreConnectTimeout, fhirStoreSocketTimeout, fhirStoreConnectionRequestTimeout, fhirStoreBaseUrl,
				fhirStoreUsername, fhirStorePassword, fhirStoreBearerToken, tokenProvider(), proxyUrl, proxyUsername,
				proxyPassword, fhirStoreHapiClientVerbose, fhirContext, localIdentifierValue, dataLogger());
	}

	public TokenProvider tokenProvider()
	{
		return new OAuth2TokenProvider(tokenClient());
	}

	public TokenClient tokenClient()
	{
		Path trustStoreOAuth2Path = checkExists(fhirStoreOAuth2TrustStore);

		String proxyUrl = fhirStoreOAuth2ProxyUrl, proxyUsername = fhirStoreOAuth2ProxyUsername,
				proxyPassword = fhirStoreOAuth2ProxyPassword;
		if (proxyUrl == null && api.getProxyConfig().isEnabled()
				&& !api.getProxyConfig().isNoProxyUrl(fhirStoreOAuth2IssuerUrl))
		{
			proxyUrl = api.getProxyConfig().getUrl();
			proxyUsername = api.getProxyConfig().getUsername();
			proxyPassword = api.getProxyConfig().getPassword() == null ? null
					: new String(api.getProxyConfig().getPassword());
		}

		return new OAuth2TokenClient(fhirStoreOAuth2IssuerUrl, fhirStoreOAuth2DiscoveryPath, fhirStoreOAuth2ClientId,
				fhirStoreOAuth2ClientSecret, fhirStoreOAuth2ConnectTimeout, fhirStoreOAuth2SocketTimeout,
				trustStoreOAuth2Path, proxyUrl, proxyUsername, proxyPassword);
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
