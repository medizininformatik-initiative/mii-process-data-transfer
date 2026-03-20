package de.medizininformatik_initiative.process.data_transfer.variables;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ProcessConfig
{
	private final Map<String, String> elements = new HashMap<>();

	public ProcessConfig(Map<String, String> elements)
	{
		this.elements.putAll(elements);
	}

	public void add(String key, String value)
	{
		if (key != null && !key.isBlank())
			elements.put(key, value);
	}

	public String toString(Map<String, String> elements)
	{
		return elements.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
				.collect(Collectors.joining(", ", "[", "]"));
	}
}
