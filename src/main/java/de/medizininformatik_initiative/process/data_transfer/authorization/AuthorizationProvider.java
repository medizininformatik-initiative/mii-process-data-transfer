package de.medizininformatik_initiative.process.data_transfer.authorization;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.ActivityDefinition;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import dev.dsf.bpe.v1.ProcessPluginApi;
import dev.dsf.fhir.authorization.process.ProcessAuthorizationHelper;
import dev.dsf.fhir.authorization.process.Recipient;
import dev.dsf.fhir.authorization.process.Requester;

public class AuthorizationProvider implements InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(AuthorizationProvider.class);

	private record ProcessAuthorization(String parentOrganizationIdentifierValue, String organizationRoleCodingValue)
	{
		public static ProcessAuthorization from(String processAuthorization)
		{
			String[] identifierRoleSplit = processAuthorization.split("\\|");
			if (identifierRoleSplit.length < 2)
				throw new IllegalArgumentException(
						"Process authorization configuration should be in format <parent-organization-identifier>|<role>, but does not contain '|'");

			return new ProcessAuthorization(identifierRoleSplit[0], identifierRoleSplit[1]);
		}
	}

	private final ProcessPluginApi api;

	private final String resourcesVersion;

	private final List<ProcessAuthorization> additionallyAllowedSenders;
	private final List<ProcessAuthorization> additionallyAllowedReceivers;

	public AuthorizationProvider(ProcessPluginApi api, String resourcesVersion, List<String> additionallyAllowedSenders,
			List<String> additionallyAllowedReceivers)
	{
		this.api = api;
		this.resourcesVersion = resourcesVersion;

		this.additionallyAllowedSenders = additionallyAllowedSenders.stream().map(ProcessAuthorization::from).toList();
		this.additionallyAllowedReceivers = additionallyAllowedReceivers.stream().map(ProcessAuthorization::from)
				.toList();
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(resourcesVersion, "resourcesVersion");

		Objects.requireNonNull(additionallyAllowedSenders, "allowedSenders");
		Objects.requireNonNull(additionallyAllowedReceivers, "allowedReceivers");
	}

	public void searchCheckAddAndUpdateAuthorizationDataSend()
	{
		searchCheckAddAndUpdateAuthorization(ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
				this::checkAddAndUpdateAuthorizationDataSend);
	}

	public void searchCheckAddAndUpdateAuthorizationDataReceive()
	{
		searchCheckAddAndUpdateAuthorization(ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourcesVersion,
				this::checkAddAndUpdateAuthorizationDataReceive);
	}

	public void checkAddAndUpdateAuthorizationDataSend(ActivityDefinition activityDefinition)
	{
		// for each entry in allowedSender: check if exists or add requester for task-data-send-start (LOCAL_ROLE,
		// LOCAL_ROLE_PRACTITIONER)
		// for each entry in allowedSender: check if exists or add recipient for task-data-send-start (LOCAL_ROLE)
		Set<Requester> requestersDataSendStart = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START)
				.collect(Collectors.toSet());
		Set<Recipient> recipientDataSendStart = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START)
				.collect(Collectors.toSet());

		List<Requester> missingRequesterDataSendStart = additionallyAllowedSenders.stream()
				.flatMap(s -> Stream.of(
						Requester.localRole(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
						Requester.localRolePractitioner(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue,
								ConstantsDataTransfer.CODE_SYSTEM_PRACTITIONER_ROLE,
								ConstantsDataTransfer.CODE_SYSTEM_PRACTITIONER_ROLE_VALUE_DSF_ADMIN)))
				.filter(notContainsNewRequester(requestersDataSendStart)).toList();
		List<Recipient> missingRecipientDataSendStart = additionallyAllowedSenders.stream()
				.map(s -> Recipient.localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue))
				.filter(notContainsNewRecipient(recipientDataSendStart)).toList();

		if (!missingRequesterDataSendStart.isEmpty() || !missingRecipientDataSendStart.isEmpty())
		{
			logger.info(
					"Adding additional allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START,
					toRequesterString(missingRequesterDataSendStart));
			logger.info(
					"Adding additional allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START,
					toRecipientString(missingRecipientDataSendStart));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START), missingRequesterDataSendStart,
					missingRecipientDataSendStart);
		}

		// for each entry in allowedReceiver: check if exists or add requester for task-data-status (LOCAL_ROLE,
		// REMOTE_ROLE)
		// for each entry in allowedSender: check if exists or add recipient for task-data-status (LOCAL_ROLE)
		Set<Requester> requestersDataStatus = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS)
				.collect(Collectors.toSet());
		Set<Recipient> recipientDataStatus = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS)
				.collect(Collectors.toSet());

		List<Requester> missingRequesterDataStatus = additionallyAllowedReceivers.stream()
				.flatMap(s -> Stream.of(
						Requester.localRole(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
						Requester.remoteRole(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue)))
				.filter(notContainsNewRequester(requestersDataStatus)).toList();
		List<Recipient> missingRecipientDataStatus = additionallyAllowedSenders.stream()
				.map(s -> Recipient.localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue))
				.filter(notContainsNewRecipient(recipientDataStatus)).toList();

		if (!missingRequesterDataStatus.isEmpty() || !missingRecipientDataStatus.isEmpty())
		{
			logger.info(
					"Adding additional allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS, toRequesterString(missingRequesterDataStatus));
			logger.info(
					"Adding additional allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS, toRecipientString(missingRecipientDataStatus));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS), missingRequesterDataStatus,
					missingRecipientDataStatus);
		}

		if (!missingRequesterDataSendStart.isEmpty() || !missingRecipientDataSendStart.isEmpty()
				|| !requestersDataStatus.isEmpty() || !recipientDataStatus.isEmpty())
			updateResource(activityDefinition);
	}

	public void checkAddAndUpdateAuthorizationDataReceive(ActivityDefinition activityDefinition)
	{
		// for each entry in allowedSender: check if exists or add requester for task-data-send (LOCAL_ROLE,
		// REMOTE_ROLE)
		Set<Requester> requestersDataSend = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND)
				.collect(Collectors.toSet());

		List<Requester> missingRequesterDataSend = additionallyAllowedSenders.stream()
				.flatMap(s -> Stream.of(
						Requester.localRole(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
						Requester.remoteRole(s.parentOrganizationIdentifierValue,
								ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue)))
				.filter(notContainsNewRequester(requestersDataSend)).toList();

		// for each entry in allowedReceivers: check if exists or add recipient for task-data-status (LOCAL_ROLE)
		Set<Recipient> recipientDataSend = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourcesVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND)
				.collect(Collectors.toSet());

		List<Recipient> missingRecipientDataSend = additionallyAllowedReceivers.stream()
				.map(s -> Recipient.localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue))
				.filter(notContainsNewRecipient(recipientDataSend)).toList();

		if (!missingRequesterDataSend.isEmpty() || !missingRecipientDataSend.isEmpty())
		{
			logger.info(
					"Adding additional allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND, toRequesterString(missingRequesterDataSend));
			logger.info(
					"Adding additional allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourcesVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND, toRecipientString(missingRecipientDataSend));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND), missingRequesterDataSend,
					missingRecipientDataSend);
			updateResource(activityDefinition);
		}
	}

	private void searchCheckAddAndUpdateAuthorization(String url, String version,
			Consumer<ActivityDefinition> checkAddAndUpdateAuthorization)
	{
		Bundle searchResult = searchActivityDefinition(url, version);
		extractActivityDefinition(searchResult).ifPresent(checkAddAndUpdateAuthorization);
	}

	private Bundle searchActivityDefinition(String url, String version)
	{
		return api.getFhirWebserviceClientProvider().getLocalWebserviceClient().search(ActivityDefinition.class,
				Map.of("url", List.of(url), "version", List.of(version)));
	}

	private Optional<ActivityDefinition> extractActivityDefinition(Bundle bundle)
	{
		return bundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof ActivityDefinition)
				.map(r -> (ActivityDefinition) r).findFirst();
	}

	private void updateResource(Resource resource)
	{
		api.getFhirWebserviceClientProvider().getLocalWebserviceClient().update(resource);
	}

	private Predicate<Requester> notContainsNewRequester(Set<Requester> existings)
	{
		return newR -> existings.stream().noneMatch(existing -> existing.requesterMatches(newR.toRequesterExtension()));
	}

	private Predicate<Recipient> notContainsNewRecipient(Set<Recipient> existings)
	{

		return newR -> existings.stream().noneMatch(existing -> existing.recipientMatches(newR.toRecipientExtension()));
	}

	private String appendVersion(String url)
	{
		return url + "|" + resourcesVersion;
	}

	private String toRequesterString(List<Requester> requesters)
	{
		return requesters.stream().map(Requester::toRequesterExtension).map(Extension::getValue)
				.filter(v -> v instanceof Coding).map(v -> (Coding) v).map(this::toParentOrganizationRoleString)
				.collect(Collectors.joining(","));
	}

	private String toRecipientString(List<Recipient> recipients)
	{
		return recipients.stream().map(Recipient::toRecipientExtension).map(Extension::getValue)
				.filter(v -> v instanceof Coding).map(v -> (Coding) v).map(this::toParentOrganizationRoleString)
				.collect(Collectors.joining(", "));
	}

	private String toParentOrganizationRoleString(Coding coding)
	{
		Extension extension = Optional
				.ofNullable(coding.getExtensionByUrl(
						ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_PARENT_ORGANIZATION_ROLE))
				.orElseGet(() -> coding.getExtensionByUrl(
						ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_PARENT_ORGANIZATION_ROLE_PRACTITIONER));
		String parentOrganization = getParentOrganizationIdentifierValue(extension);
		String role = getRoleValue(extension);
		return parentOrganization + "|" + role + "|" + coding.getCode();
	}

	private String getParentOrganizationIdentifierValue(Extension extension)
	{
		return ((Identifier) extension.getExtensionByUrl(
				ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_PARENT_ORGANIZATION_ROLE_PARENT_ORGANIZATION)
				.getValue()).getValue();
	}

	private String getRoleValue(Extension extension)
	{
		return ((Coding) extension.getExtensionByUrl(
				ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_PARENT_ORGANIZATION_ROLE_ORGANIZATION_ROLE)
				.getValue()).getCode();
	}
}
