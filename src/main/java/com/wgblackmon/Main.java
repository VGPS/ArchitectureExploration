package com.wgblackmon;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.wgblackmon.harness.Agent;
import com.wgblackmon.harness.Tool;
import com.wgblackmon.mcp.client.McpToolClient;

import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        String classpath = System.getProperty("java.class.path");
        try (McpToolClient mcp = McpToolClient.connect("java", "-cp", classpath, "com.wgblackmon.mcp.server.FilesystemMcpServer")) {
            List<Tool> tools = mcp.tools();

            AnthropicClient client = AnthropicOkHttpClient.fromEnv();
            Agent agent = new Agent(client, "claude-opus-5",
                    "You are a debugging agent. Use the filesystem tools to inspect this repository before answering.",
                    tools);

            String question = args.length > 0 ? args[0] : "List the files in this project and summarize what the repository contains.";
            System.out.println(agent.run(question));
        }
    }
}
