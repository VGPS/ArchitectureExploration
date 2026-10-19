package com.wgblackmon.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgblackmon.harness.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Connects to an MCP server over stdio and exposes its tools as harness {@link Tool}s.
 * This is the client side of the permission boundary: the harness only ever sees the
 * tool names and schemas the server chooses to publish.
 */
public class McpToolClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpSyncClient client;

    private McpToolClient(McpSyncClient client) {
        this.client = client;
    }

    public static McpToolClient connect(String command, String... args) {
        ServerParameters params = ServerParameters.builder(command).args(args).build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("architecture-exploration-harness", "0.1.0"))
                .build();
        client.initialize();
        return new McpToolClient(client);
    }

    public List<Tool> tools() {
        return client.listTools().tools().stream()
                .map(this::toHarnessTool)
                .collect(Collectors.toList());
    }

    private Tool toHarnessTool(McpSchema.Tool mcpTool) {
        return new Tool() {
            @Override
            public String name() {
                return mcpTool.name();
            }

            @Override
            public String description() {
                return mcpTool.description();
            }

            @Override
            public Map<String, Object> inputSchema() {
                return mcpTool.inputSchema();
            }

            @Override
            public String execute(JsonNode input) {
                Map<String, Object> arguments = MAPPER.convertValue(input, new TypeReference<Map<String, Object>>() {
                });
                McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(mcpTool.name(), arguments));
                String text = result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .collect(Collectors.joining("\n"));
                if (Boolean.TRUE.equals(result.isError())) {
                    throw new RuntimeException(text);
                }
                return text;
            }
        };
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
