package de.medizininformatik_initiative.process.data_transfer;

import de.medizininformatik_initiative.processes.common.util.ConstantsBase;

public interface ConstantsDataTransfer
{
	String PROCESS_NAME_DATA_SEND = "dataSend";
	String PROCESS_NAME_DATA_RECEIVE = "dataReceive";

	String PROCESS_NAME_FULL_DATA_SEND = ConstantsBase.PROCESS_MII_NAME_BASE + PROCESS_NAME_DATA_SEND;
	String PROCESS_NAME_FULL_DATA_RECEIVE = ConstantsBase.PROCESS_MII_NAME_BASE + PROCESS_NAME_DATA_RECEIVE;

	String PROFILE_TASK_DATA_SEND_START = "http://medizininformatik-initiative.de/fhir/StructureDefinition/task-data-send-start";
	String PROFILE_TASK_DATA_SEND_START_PROCESS_URI = ConstantsBase.PROCESS_MII_URI_BASE + PROCESS_NAME_DATA_SEND;
	String PROFILE_TASK_DATA_SEND_START_MESSAGE_NAME = "dataSendStart";

	String PROFILE_TASK_DATA_SEND = "http://medizininformatik-initiative.de/fhir/StructureDefinition/task-data-send";
	String PROFILE_TASK_DATA_SEND_PROCESS_URI = ConstantsBase.PROCESS_MII_URI_BASE + PROCESS_NAME_DATA_RECEIVE;
	String PROFILE_TASK_DATA_SEND_MESSAGE_NAME = "dataSend";

	String PROFILE_TASK_DATA_STATUS = "http://medizininformatik-initiative.de/fhir/StructureDefinition/task-data-status";
	String PROFILE_TASK_DATA_STATUS_PROCESS_URI = ConstantsBase.PROCESS_MII_URI_BASE + PROCESS_NAME_DATA_SEND;
	String PROFILE_TASK_DATA_STATUS_MESSAGE_NAME = "dataStatus";

	String BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER = "projectIdentifier";
	String BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER = "dms-identifier";
	String BPMN_EXECUTION_VARIABLE_INITIAL_DOCUMENT_REFERENCE = "initialDocumentReference";
	String BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE = "transferDocumentReference";
	String BPMN_EXECUTION_VARIABLE_TRANSFER_DOCUMENT_REFERENCE_LOCATION = "transferDocumentReferenceLocation";
	String BPMN_EXECUTION_VARIABLE_DATA_RESOURCES = "dataResources";
	String BPMN_EXECUTION_VARIABLE_BINARY_REFERENCES_LIST_RESOURCE = "binaryReferencesListResource";

	String BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR = "dataSendError";
	String BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR_MESSAGE = "dataSendErrorMessage";
	String BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR = "dataReceiveError";
	String BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE = "dataReceiveErrorMessage";

	String CODESYSTEM_DATA_TRANSFER = "http://medizininformatik-initiative.de/fhir/CodeSystem/data-transfer";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER = "dms-identifier";
	String CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER = "project-identifier";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION = "document-reference-location";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS = "data-set-status";

	String EXTENSION_LIST_ENTRY_MIMETYPE = "http://medizininformatik-initiative.de/fhir/StructureDefinition/extension-list-entry-item-mimetype";
}
