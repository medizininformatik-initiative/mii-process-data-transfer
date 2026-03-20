package de.medizininformatik_initiative.process.data_transfer.authorization;

import java.util.ArrayList;
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
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.processes.common.util.ConstantsBase;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.service.process.ProcessAuthorizationHelper;
import dev.dsf.bpe.v2.service.process.Recipient;
import dev.dsf.bpe.v2.service.process.Requester;

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

	private final List<ProcessAuthorization> additionallyAllowedSenders;
	private final List<ProcessAuthorization> additionallyAllowedReceivers;

	public AuthorizationProvider(ProcessPluginApi api, List<String> additionallyAllowedSenders,
			List<String> additionallyAllowedReceivers)
	{
		this.api = api;

		additionallyAllowedSenders
				.add(ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM
						+ "|DIC");
		this.additionallyAllowedSenders = additionallyAllowedSenders.stream().map(ProcessAuthorization::from).toList();

		additionallyAllowedReceivers
				.add(ConstantsBase.NAMINGSYSTEM_DSF_ORGANIZATION_IDENTIFIER_MEDICAL_INFORMATICS_INITIATIVE_CONSORTIUM
						+ "|DMS");
		this.additionallyAllowedReceivers = additionallyAllowedReceivers.stream().map(ProcessAuthorization::from)
				.toList();
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(additionallyAllowedSenders, "allowedSenders");
		Objects.requireNonNull(additionallyAllowedReceivers, "allowedReceivers");
	}

	public void searchCheckAddAndUpdateAuthorizationDataSend()
	{
		searchCheckAddAndUpdateAuthorization(ConstantsDataTransfer.PROCESS_URL_DATA_SEND,
				this::checkAddAndUpdateAuthorizationDataSend);
	}

	public void searchCheckAddAndUpdateAuthorizationDataReceive()
	{
		searchCheckAddAndUpdateAuthorization(ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE,
				this::checkAddAndUpdateAuthorizationDataReceive);
	}

	public void checkAddAndUpdateAuthorizationDataSend(ActivityDefinition activityDefinition)
	{
		String resourceVersion = api.getProcessPluginDefinition().getResourceVersion();
		boolean processAuthorizationChanged = false;

		// for each entry in allowedSender: check if exists or add requester for task-data-send-start (LOCAL_ROLE,
		// LOCAL_ROLE_PRACTITIONER)
		// for each entry in allowedSender: check if exists or add recipient for task-data-send-start (LOCAL_ROLE)
		Set<Requester> currentRequestersDataSendStart = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START)
				.collect(Collectors.toSet());
		Set<Recipient> currentRecipientsDataSendStart = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START)
				.collect(Collectors.toSet());

		Set<Requester> configuredRequestersDataSendStart = additionallyAllowedSenders.stream().flatMap(s -> Stream.of(
				api.getProcessAuthorizationHelper().getRequesterFactory().localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
				api.getProcessAuthorizationHelper().getRequesterFactory().localRolePractitioner(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue, ConstantsDataTransfer.CODE_SYSTEM_PRACTITIONER_ROLE,
						ConstantsDataTransfer.CODE_SYSTEM_PRACTITIONER_ROLE_VALUE_DSF_ADMIN)))
				.collect(Collectors.toSet());
		Set<Recipient> configuredRecipientsDataSendStart = additionallyAllowedSenders.stream()
				.map(s -> api.getProcessAuthorizationHelper().getRecipientFactory().localRole(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue))
				.collect(Collectors.toSet());

		Set<Requester> removedOldRequestersDataSendStart = currentRequestersDataSendStart.stream()
				.filter(containsOldRequester(configuredRequestersDataSendStart)).collect(Collectors.toSet());
		Set<Recipient> removedOldRecipientsDataSendStart = currentRecipientsDataSendStart.stream()
				.filter(containsOldRecipient(configuredRecipientsDataSendStart)).collect(Collectors.toSet());

		Set<Requester> missingRequestersDataSendStart = configuredRequestersDataSendStart.stream()
				.filter(notContainsNewRequester(removedOldRequestersDataSendStart)).collect(Collectors.toSet());
		Set<Recipient> missingRecipientsDataSendStart = configuredRecipientsDataSendStart.stream()
				.filter(notContainsNewRecipient(removedOldRecipientsDataSendStart)).collect(Collectors.toSet());

		if (!missingRequestersDataSendStart.isEmpty() || !missingRecipientsDataSendStart.isEmpty()
				|| removedOldRequestersDataSendStart.size() != currentRequestersDataSendStart.size()
				|| removedOldRecipientsDataSendStart.size() != currentRecipientsDataSendStart.size())
		{
			removedOldRequestersDataSendStart.addAll(missingRequestersDataSendStart);
			removedOldRecipientsDataSendStart.addAll(missingRecipientsDataSendStart);

			logger.info(
					"Adjusting allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START,
					toRequesterString(removedOldRequestersDataSendStart));
			logger.info(
					"Adjusting allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START,
					toRecipientString(removedOldRecipientsDataSendStart));

			removeAllAuthorizationExtensions(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_START),
					removedOldRequestersDataSendStart, removedOldRecipientsDataSendStart);
			processAuthorizationChanged = true;
		}

		// for each entry in allowedReceiver: check if exists or add requester for task-data-status (LOCAL_ROLE,
		// REMOTE_ROLE)
		// for each entry in allowedSender: check if exists or add recipient for task-data-status (LOCAL_ROLE)
		Set<Requester> currentRequestersDataStatus = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS)
				.collect(Collectors.toSet());
		Set<Recipient> currentRecipientsDataStatus = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS)
				.collect(Collectors.toSet());

		Set<Requester> configuredRequestersDataStatus = additionallyAllowedReceivers.stream().flatMap(s -> Stream.of(
				api.getProcessAuthorizationHelper().getRequesterFactory().localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
				api.getProcessAuthorizationHelper().getRequesterFactory().remoteRole(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue)))
				.collect(Collectors.toSet());
		Set<Recipient> configuredRecipientsDataStatus = additionallyAllowedSenders.stream()
				.map(s -> api.getProcessAuthorizationHelper().getRecipientFactory().localRole(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue))
				.collect(Collectors.toSet());

		Set<Requester> removedOldRequestersDataStatus = currentRequestersDataStatus.stream()
				.filter(containsOldRequester(configuredRequestersDataStatus)).collect(Collectors.toSet());
		Set<Recipient> removedOldRecipientsDataStatus = currentRecipientsDataStatus.stream()
				.filter(containsOldRecipient(configuredRecipientsDataStatus)).collect(Collectors.toSet());

		Set<Requester> missingRequestersDataStatus = configuredRequestersDataStatus.stream()
				.filter(notContainsNewRequester(removedOldRequestersDataStatus)).collect(Collectors.toSet());
		Set<Recipient> missingRecipientsDataStatus = configuredRecipientsDataStatus.stream()
				.filter(notContainsNewRecipient(removedOldRecipientsDataStatus)).collect(Collectors.toSet());

		if (!missingRequestersDataStatus.isEmpty() || !missingRecipientsDataStatus.isEmpty()
				|| removedOldRequestersDataStatus.size() != currentRequestersDataStatus.size()
				|| removedOldRecipientsDataStatus.size() != currentRecipientsDataStatus.size())
		{
			removedOldRequestersDataStatus.addAll(missingRequestersDataStatus);
			removedOldRecipientsDataStatus.addAll(missingRecipientsDataStatus);

			logger.info(
					"Adjusting allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS, toRequesterString(removedOldRequestersDataStatus));
			logger.info(
					"Adjusting allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_SEND, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS, toRecipientString(removedOldRecipientsDataStatus));

			removeAllAuthorizationExtensions(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_STATUS), removedOldRequestersDataStatus,
					removedOldRecipientsDataStatus);
			processAuthorizationChanged = true;
		}

		if (processAuthorizationChanged)
			updateResource(activityDefinition);
	}

	public void checkAddAndUpdateAuthorizationDataReceive(ActivityDefinition activityDefinition)
	{
		String resourceVersion = api.getProcessPluginDefinition().getResourceVersion();

		// for each entry in allowedSender: check if exists or add requester for task-data-send (LOCAL_ROLE,
		// REMOTE_ROLE)
		// for each entry in allowedReceivers: check if exists or add recipient for task-data-status (LOCAL_ROLE)
		Set<Requester> currentRequestersDataSend = api.getProcessAuthorizationHelper()
				.getRequesters(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND)
				.collect(Collectors.toSet());
		Set<Recipient> currentRecipientsDataSend = api.getProcessAuthorizationHelper()
				.getRecipients(activityDefinition, ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourceVersion,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
						ConstantsDataTransfer.PROFILE_TASK_DATA_SEND)
				.collect(Collectors.toSet());

		Set<Requester> configuredRequestersDataSend = additionallyAllowedSenders.stream().flatMap(s -> Stream.of(
				api.getProcessAuthorizationHelper().getRequesterFactory().localRole(s.parentOrganizationIdentifierValue,
						ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE, s.organizationRoleCodingValue),
				api.getProcessAuthorizationHelper().getRequesterFactory().remoteRole(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue)))
				.collect(Collectors.toSet());
		Set<Recipient> configuredRecipientsDataSend = additionallyAllowedReceivers.stream()
				.map(s -> api.getProcessAuthorizationHelper().getRecipientFactory().localRole(
						s.parentOrganizationIdentifierValue, ConstantsDataTransfer.CODE_SYSTEM_ORGANIZATION_ROLE,
						s.organizationRoleCodingValue))
				.collect(Collectors.toSet());

		Set<Requester> removedOldRequestersDataSend = currentRequestersDataSend.stream()
				.filter(containsOldRequester(configuredRequestersDataSend)).collect(Collectors.toSet());
		Set<Recipient> removedOldRecipientsDataSend = currentRecipientsDataSend.stream()
				.filter(containsOldRecipient(configuredRecipientsDataSend)).collect(Collectors.toSet());

		List<Requester> missingRequestersDataSend = configuredRequestersDataSend.stream()
				.filter(notContainsNewRequester(removedOldRequestersDataSend)).toList();
		List<Recipient> missingRecipientsDataSend = configuredRecipientsDataSend.stream()
				.filter(notContainsNewRecipient(removedOldRecipientsDataSend)).toList();

		if (!missingRequestersDataSend.isEmpty() || !missingRecipientsDataSend.isEmpty()
				|| removedOldRequestersDataSend.size() != currentRequestersDataSend.size()
				|| removedOldRecipientsDataSend.size() != currentRecipientsDataSend.size())
		{
			removedOldRequestersDataSend.addAll(missingRequestersDataSend);
			removedOldRecipientsDataSend.addAll(missingRecipientsDataSend);

			logger.info(
					"Adjusting allowed data-set senders to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND, toRequesterString(removedOldRequestersDataSend));
			logger.info(
					"Adjusting allowed data-set receivers to the authorization rules for process '{}', version '{}', message-name '{}' and task-profile '{}': {}",
					ConstantsDataTransfer.PROCESS_URL_DATA_RECEIVE, resourceVersion,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND, toRecipientString(removedOldRecipientsDataSend));

			removeAllAuthorizationExtensions(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND));
			api.getProcessAuthorizationHelper().add(activityDefinition,
					ConstantsDataTransfer.PROFILE_TASK_DATA_SEND_MESSAGE_NAME,
					appendVersion(ConstantsDataTransfer.PROFILE_TASK_DATA_SEND), removedOldRequestersDataSend,
					removedOldRecipientsDataSend);
			updateResource(activityDefinition);
		}
	}

	private void searchCheckAddAndUpdateAuthorization(String url,
			Consumer<ActivityDefinition> checkAddAndUpdateAuthorization)
	{
		Bundle searchResult = searchActivityDefinition(url);
		extractActivityDefinition(searchResult).ifPresent(checkAddAndUpdateAuthorization);
	}

	private Bundle searchActivityDefinition(String url)
	{
		return api.getDsfClientProvider().getLocal().search(ActivityDefinition.class,
				Map.of("url", List.of(url), "version", List.of(api.getProcessPluginDefinition().getResourceVersion())));
	}

	private Optional<ActivityDefinition> extractActivityDefinition(Bundle bundle)
	{
		return bundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof ActivityDefinition)
				.map(r -> (ActivityDefinition) r).findFirst();
	}

	private void updateResource(Resource resource)
	{
		api.getDsfClientProvider().getLocal().update(resource);
	}

	private Predicate<Requester> containsOldRequester(Set<Requester> configureds)
	{
		return oldR -> configureds.stream()
				.anyMatch(configured -> configured.requesterMatches(oldR.toRequesterExtension()));
	}

	private Predicate<Recipient> containsOldRecipient(Set<Recipient> configureds)
	{
		return oldR -> configureds.stream()
				.anyMatch(configured -> configured.recipientMatches(oldR.toRecipientExtension()));
	}

	private Predicate<Requester> notContainsNewRequester(Set<Requester> existings)
	{
		return newR -> existings.stream().noneMatch(existing -> existing.requesterMatches(newR.toRequesterExtension()));
	}

	private Predicate<Recipient> notContainsNewRecipient(Set<Recipient> existings)
	{

		return newR -> existings.stream().noneMatch(existing -> existing.recipientMatches(newR.toRecipientExtension()));
	}

	private void removeAllAuthorizationExtensions(ActivityDefinition activityDefinition, String messageName,
			String taskProfile)
	{
		List<Extension> extensions = activityDefinition
				.getExtensionsByUrl(ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION).stream()
				.filter(extension -> !messageName.equals(((StringType) extension
						.getExtensionByUrl(ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_MESSAGE_NAME)
						.getValue()).getValue())
						&& !taskProfile.equals(((CanonicalType) extension
								.getExtensionByUrl(
										ProcessAuthorizationHelper.EXTENSION_PROCESS_AUTHORIZATION_TASK_PROFILE)
								.getValue()).getValue()))
				.toList();
		activityDefinition.setExtension(new ArrayList<>(extensions));
	}

	private String appendVersion(String url)
	{
		return url + "|" + api.getProcessPluginDefinition().getResourceVersion();
	}

	private String toRequesterString(Set<Requester> requesters)
	{
		return requesters.stream().map(Requester::toRequesterExtension).map(Extension::getValue)
				.filter(v -> v instanceof Coding).map(v -> (Coding) v).map(this::toParentOrganizationRoleString)
				.collect(Collectors.joining(","));
	}

	private String toRecipientString(Set<Recipient> recipients)
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
