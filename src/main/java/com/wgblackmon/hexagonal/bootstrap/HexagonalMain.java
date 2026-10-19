package com.wgblackmon.hexagonal.bootstrap;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.wgblackmon.hexagonal.adapter.mcp.SpringMcpToolSource;
import com.wgblackmon.hexagonal.adapter.model.AnthropicModelClient;
import com.wgblackmon.hexagonal.domain.Agent;
import com.wgblackmon.hexagonal.domain.Tool;

import java.util.List;

/**
 * Composition root for Track B. Wires an {@link AnthropicModelClient} and the tools
 * discovered via {@link SpringMcpToolSource} into a {@link Agent} and asks it something.
 * Connects to the exact same {@code com.wgblackmon.mcp.server.FilesystemMcpServer} Track A
 * uses — no duplicate server — so the two tracks are directly comparable against identical
 * tools, differing only in the harness wired around them.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class HexagonalMain {

    public static void main(String[] args) throws Exception {
        String classpath = System.getProperty("java.class.path");
        try (SpringMcpToolSource mcp = SpringMcpToolSource.connect(
                "java", "-cp", classpath, "com.wgblackmon.mcp.server.FilesystemMcpServer")) {
            List<Tool> tools = mcp.tools();

            AnthropicClient anthropicClient = AnthropicOkHttpClient.fromEnv();
            AnthropicModelClient modelClient = new AnthropicModelClient(anthropicClient, "claude-opus-5");
            Agent agent = new Agent(modelClient,
                    "You are a debugging agent for the hexagonal Track B harness. Use the filesystem tools to inspect this repository before answering.",
                    tools);

            String question = args.length > 0 ? args[0]
                    : "List the files in this project and summarize what the repository contains.";
            System.out.println(agent.run(question));
        }
    }
}
