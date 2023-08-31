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

	String PROFILE_TASK_DATA_RECEIVE = "http://medizininformatik-initiative.de/fhir/StructureDefinition/task-data-receive";
	String PROFILE_TASK_DATA_RECEIVE_PROCESS_URI = ConstantsBase.PROCESS_MII_URI_BASE + PROCESS_NAME_DATA_SEND;
	String PROFILE_TASK_DATA_RECEIVE_MESSAGE_NAME = "dataReceive";

	String BPMN_EXECUTION_VARIABLE_PROJECT_IDENTIFIER = "projectIdentifier";
	String BPMN_EXECUTION_VARIABLE_DMS_IDENTIFIER = "dms-identifier";
	String BPMN_EXECUTION_VARIABLE_DATA_SET = "dataSet";
	String BPMN_EXECUTION_VARIABLE_DATA_SET_ENCRYPTED = "dataSetEncrypted";
	String BPMN_EXECUTION_VARIABLE_DATA_SET_REFERENCE = "dataSetReference";
	String BPMN_EXECUTION_VARIABLE_DOCUMENT_REFERENCE = "documentReference";
	String BPMN_EXECUTION_VARIABLE_DATA_RESOURCE = "dataResource";
	String BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR = "dataSendError";
	String BPMN_EXECUTION_VARIABLE_DATA_SEND_ERROR_MESSAGE = "dataSendErrorMessage";
	String BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR = "dataReceiveError";
	String BPMN_EXECUTION_VARIABLE_DATA_RECEIVE_ERROR_MESSAGE = "dataReceiveErrorMessage";

	String CODESYSTEM_DATA_TRANSFER = "http://medizininformatik-initiative.de/fhir/CodeSystem/data-transfer";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DMS_IDENTIFIER = "dms-identifier";
	String CODESYSTEM_DATA_TRANSFER_VALUE_PROJECT_IDENTIFIER = "project-identifier";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_REFERENCE = "data-set-reference";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DOCUMENT_REFERENCE_LOCATION = "document-reference-location";
	String CODESYSTEM_DATA_TRANSFER_VALUE_DATA_SET_STATUS = "data-set-status";

	String CODESYSTEM_DATA_SET_STATUS = "http://medizininformatik-initiative.de/fhir/CodeSystem/data-set-status";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_ALLOWED = "not-allowed";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_NOT_REACHABLE = "not-reachable";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_MISSING = "receipt-missing";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_OK = "receipt-ok";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIPT_ERROR = "receipt-error";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_OK = "receive-ok";
	String CODESYSTEM_DATA_SET_STATUS_VALUE_RECEIVE_ERROR = "receive-error";

	String EXTENSION_DATA_SET_STATUS_ERROR_URL = "http://medizininformatik-initiative.de/fhir/StructureDefinition/extension-data-set-status-error";
}
