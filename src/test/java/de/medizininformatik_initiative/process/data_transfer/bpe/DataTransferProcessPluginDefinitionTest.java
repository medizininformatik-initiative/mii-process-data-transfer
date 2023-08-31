package de.medizininformatik_initiative.process.data_transfer.bpe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import de.medizininformatik_initiative.process.data_transfer.ConstantsDataTransfer;
import de.medizininformatik_initiative.process.data_transfer.DataTransferProcessPluginDefinition;
import dev.dsf.bpe.v1.ProcessPluginDefinition;

public class DataTransferProcessPluginDefinitionTest
{
	@Test
	public void testResourceLoading()
	{
		ProcessPluginDefinition definition = new DataTransferProcessPluginDefinition();
		Map<String, List<String>> resourcesByProcessId = definition.getFhirResourcesByProcessId();

		var receive = resourcesByProcessId.get(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_RECEIVE);
		assertNotNull(receive);
		assertEquals(10, receive.stream().filter(this::exists).count());

		var send = resourcesByProcessId.get(ConstantsDataTransfer.PROCESS_NAME_FULL_DATA_SEND);
		assertNotNull(send);
		assertEquals(10, send.stream().filter(this::exists).count());
	}

	private boolean exists(String file)
	{
		return getClass().getClassLoader().getResourceAsStream(file) != null;
	}
}
