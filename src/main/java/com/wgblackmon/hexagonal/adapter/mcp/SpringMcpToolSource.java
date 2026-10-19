package com.wgblackmon.hexagonal.adapter.mcp;

import com.wgblackmon.hexagonal.domain.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Connects to an MCP server over stdio — using the exact same {@code ServerParameters} /
 * {@code StdioClientTransport} / {@code McpClient.sync(...)} pattern already verified in
 * Track A's {@code mcp.client.McpToolClient} — and exposes its tools as domain
 * {@link Tool}s via Spring AI's {@code SyncMcpToolCallbackProvider} instead of Track A's
 * hand-rolled conversion.
 *
 * {@code SyncMcpToolCallbackProvider} implements
 * {@code ApplicationListener<McpToolsChangedEvent>} so a Spring context can invalidate
 * its cache when the server's tool list changes. There is no Spring context here by
 * design (that event is exactly the piece of ceremony this adapter skips to stay a plain
 * {@code main()}-constructible, debugger-steppable object), so {@link #tools()} is a
 * one-time snapshot — the same characteristic Track A's {@code listTools()} call already
 * has.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class SpringMcpToolSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpringMcpToolSource.class);

    private final McpSyncClient client;

    private SpringMcpToolSource(McpSyncClient client) {
        this.client = client;
    }

    public static SpringMcpToolSource connect(String command, String... args) {
        log.debug("connect() | command={}, args={}", command, (Object) args);

        ServerParameters params = ServerParameters.builder(command).args(args).build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("architecture-exploration-hexagonal", "0.1.0"))
                .build();
        client.initialize();

        SpringMcpToolSource result = new SpringMcpToolSource(client);

        log.debug("connect() | return={}", result);
        return result;
    }

    public List<Tool> tools() {
        log.debug("tools() | (no args)");

        SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(client);
        List<Tool> result = List.of(provider.getToolCallbacks()).stream()
                .map(callback -> (Tool) new SpringMcpToolAdapter(callback))
                .collect(Collectors.toList());

        log.debug("tools() | return={}", result);
        return result;
    }

    @Override
    public void close() {
        log.debug("close() | (no args)");

        client.closeGracefully();

        log.debug("close() | return=void");
    }
}
