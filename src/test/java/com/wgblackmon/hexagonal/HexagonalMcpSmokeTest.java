package com.wgblackmon.hexagonal;

import com.wgblackmon.hexagonal.adapter.mcp.SpringMcpToolSource;
import com.wgblackmon.hexagonal.domain.Tool;

import java.util.List;
import java.util.Map;

/**
 * Manual smoke test (no test framework in this repo yet, same as Track A's
 * {@code mcp.McpSmokeTest}) — but also the spec-driven contract check for this slice:
 * the filesystem tools' schemas already exist and are already covered by Track A's smoke
 * test, so what's actually being verified here is the bridge itself — "MCP schema →
 * domain {@code Tool} shape, via Spring AI" — not a new tool.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public class HexagonalMcpSmokeTest {

    public static void main(String[] args) throws Exception {
        String classpath = System.getProperty("java.class.path");
        try (SpringMcpToolSource mcp = SpringMcpToolSource.connect(
                "java", "-cp", classpath, "com.wgblackmon.mcp.server.FilesystemMcpServer")) {
            List<Tool> tools = mcp.tools();
            System.out.println("Discovered tools (via Spring AI ToolCallback bridge):");
            for (Tool t : tools) {
                System.out.println("  - " + t.name() + ": " + t.description());
            }
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("list_files")), "list_files not discovered");
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("read_file")), "read_file not discovered");
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("grep")), "grep not discovered");

            Tool listFiles = tools.stream().filter(t -> t.name().equals("list_files")).findFirst().orElseThrow();
            String listing = listFiles.execute(Map.of("path", "src"));
            System.out.println("\nlist_files(src):\n" + listing);
            assertTrue(listing.contains("main"), "list_files(src) missing 'main'");
            assertTrue(listing.contains("test"), "list_files(src) missing 'test'");

            Tool grep = tools.stream().filter(t -> t.name().equals("grep")).findFirst().orElseThrow();
            String matches = grep.execute(Map.of("pattern", "class Agent", "path", "src"));
            System.out.println("\ngrep('class Agent'):\n" + matches);
            assertTrue(matches.contains("harness") && matches.contains("Agent.java"), "grep did not find harness.Agent");
            assertTrue(matches.contains("hexagonal") && matches.contains("domain") && matches.contains("Agent.java"),
                    "grep did not find hexagonal.domain.Agent");

            System.out.println("\nAll assertions passed.");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
