package com.wgblackmon.mcp;

import com.wgblackmon.harness.Tool;
import com.wgblackmon.mcp.client.McpToolClient;

import java.util.List;

/**
 * Manual smoke test (not a JUnit test — no test framework in this repo yet):
 * launches the filesystem MCP server as a subprocess and exercises it over stdio.
 */
public class McpSmokeTest {

    public static void main(String[] args) throws Exception {
        String classpath = System.getProperty("java.class.path");
        try (McpToolClient mcp = McpToolClient.connect("java", "-cp", classpath, "com.wgblackmon.mcp.server.FilesystemMcpServer")) {
            List<Tool> tools = mcp.tools();
            System.out.println("Discovered tools:");
            for (Tool t : tools) {
                System.out.println("  - " + t.name() + ": " + t.description());
            }

            Tool listFiles = tools.stream().filter(t -> t.name().equals("list_files")).findFirst().orElseThrow();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String listing = listFiles.execute(mapper.readTree("{\"path\": \"src\"}"));
            System.out.println("\nlist_files(src):\n" + listing);

            Tool grep = tools.stream().filter(t -> t.name().equals("grep")).findFirst().orElseThrow();
            String matches = grep.execute(mapper.readTree("{\"pattern\": \"class Agent\", \"path\": \"src\"}"));
            System.out.println("\ngrep('class Agent'):\n" + matches);
        }
    }
}
