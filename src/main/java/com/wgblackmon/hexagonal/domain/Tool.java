package com.wgblackmon.hexagonal.domain;

import java.util.Map;

/**
 * A capability the {@link Agent} can invoke — the domain's driven port for tool execution.
 *
 * Deliberately JDK-only: unlike {@code harness.Tool} (Track A), the input type here is
 * {@code Map<String, Object>} rather than a Jackson {@code JsonNode}, so no serialization
 * library leaks into the domain. Adapters (e.g. {@code adapter.mcp.SpringMcpToolAdapter})
 * own the conversion to/from whatever wire format their backing tool actually speaks.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public interface Tool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    String execute(Map<String, Object> input) throws Exception;
}
