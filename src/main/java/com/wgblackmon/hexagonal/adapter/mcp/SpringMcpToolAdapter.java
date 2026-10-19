package com.wgblackmon.hexagonal.adapter.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgblackmon.hexagonal.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapts a single Spring AI {@link ToolCallback} — itself backed by one MCP tool, via
 * {@code SyncMcpToolCallbackProvider} — into the domain's {@link Tool} port.
 *
 * {@code ToolCallback.getToolDefinition().inputSchema()} returns the schema as a JSON
 * string rather than a parsed map, so this adapter owns the Jackson parsing; that's fine
 * here since adapters (unlike {@code domain}) are allowed infrastructure dependencies.
 *
 * {@code ToolCallback.call(String)} itself returns Spring AI's own JSON-serialized form
 * of the MCP {@code CallToolResult} content list (e.g. {@code [{"text":"..."}]}), not
 * plain text. {@link #execute} unwraps that so {@code domain.Tool.execute()} returns the
 * same plain-text shape regardless of which adapter backs it — Track A's hand-rolled MCP
 * adapter already returns plain text, and the domain port's contract shouldn't vary by
 * adapter.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class SpringMcpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpringMcpToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback toolCallback;

    public SpringMcpToolAdapter(ToolCallback toolCallback) {
        log.debug("<init>() | toolCallback={}", toolCallback);

        this.toolCallback = toolCallback;

        log.debug("<init>() | return=void");
    }

    @Override
    public String name() {
        log.debug("name() | (no args)");

        String result = toolCallback.getToolDefinition().name();

        log.debug("name() | return={}", result);
        return result;
    }

    @Override
    public String description() {
        log.debug("description() | (no args)");

        String result = toolCallback.getToolDefinition().description();

        log.debug("description() | return={}", result);
        return result;
    }

    @Override
    public Map<String, Object> inputSchema() {
        log.debug("inputSchema() | (no args)");

        Map<String, Object> result;
        try {
            result = MAPPER.readValue(toolCallback.getToolDefinition().inputSchema(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid input schema for tool " + toolCallback.getToolDefinition().name(), e);
        }

        log.debug("inputSchema() | return={}", result);
        return result;
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        log.debug("execute() | input={}", input);

        String argsJson = MAPPER.writeValueAsString(input);
        String rawResult = toolCallback.call(argsJson);
        String result = extractText(rawResult);

        log.debug("execute() | return={}", result);
        return result;
    }

    private static String extractText(String rawResult) throws Exception {
        List<Map<String, Object>> content = MAPPER.readValue(rawResult, new TypeReference<List<Map<String, Object>>>() {
        });
        return content.stream()
                .map(item -> item.get("text"))
                .filter(text -> text != null)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
    }
}
