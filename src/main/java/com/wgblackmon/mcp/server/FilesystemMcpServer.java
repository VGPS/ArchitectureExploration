package com.wgblackmon.mcp.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only filesystem tools over MCP, confined to {@code ROOT}. This is the permission
 * boundary: the agent on the other side of stdio never gets a raw shell or a raw path.
 */
public class FilesystemMcpServer {

    private static final Path ROOT = Path.of(System.getProperty("mcp.fs.root", ".")).toAbsolutePath().normalize();

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("filesystem-mcp-server", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(listFilesTool(), (exchange, request) -> listFiles(request))
                .toolCall(readFileTool(), (exchange, request) -> readFile(request))
                .toolCall(grepTool(), (exchange, request) -> grep(request))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
    }

    private static McpSchema.Tool listFilesTool() {
        return McpSchema.Tool.builder("list_files", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "Directory, relative to the project root. Defaults to '.'")),
                "required", List.of()
        )).description("List files and directories under a given path, relative to the project root.").build();
    }

    private static McpSchema.Tool readFileTool() {
        return McpSchema.Tool.builder("read_file", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "File path, relative to the project root")),
                "required", List.of("path")
        )).description("Read the full contents of a text file, relative to the project root.").build();
    }

    private static McpSchema.Tool grepTool() {
        return McpSchema.Tool.builder("grep", Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Regular expression to search for"),
                        "path", Map.of("type", "string", "description", "Directory to search under, relative to the project root. Defaults to '.'")
                ),
                "required", List.of("pattern")
        )).description("Search files under a directory for lines matching a regex pattern.").build();
    }

    private static McpSchema.CallToolResult listFiles(McpSchema.CallToolRequest request) {
        String relative = (String) request.arguments().getOrDefault("path", ".");
        try {
            Path dir = resolve(relative);
            try (Stream<Path> entries = Files.list(dir)) {
                String listing = entries
                        .map(p -> ROOT.relativize(p) + (Files.isDirectory(p) ? "/" : ""))
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder().addTextContent(listing).build();
            }
        } catch (Exception e) {
            return error(e);
        }
    }

    private static McpSchema.CallToolResult readFile(McpSchema.CallToolRequest request) {
        String relative = (String) request.arguments().get("path");
        try {
            Path file = resolve(relative);
            return McpSchema.CallToolResult.builder().addTextContent(Files.readString(file)).build();
        } catch (Exception e) {
            return error(e);
        }
    }

    private static McpSchema.CallToolResult grep(McpSchema.CallToolRequest request) {
        String patternText = (String) request.arguments().get("pattern");
        String relative = (String) request.arguments().getOrDefault("path", ".");
        try {
            Path dir = resolve(relative);
            Pattern pattern = Pattern.compile(patternText);
            try (Stream<Path> walk = Files.walk(dir)) {
                String matches = walk
                        .filter(Files::isRegularFile)
                        .flatMap(p -> matchingLines(p, pattern))
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder().addTextContent(matches.isEmpty() ? "(no matches)" : matches).build();
            }
        } catch (Exception e) {
            return error(e);
        }
    }

    private static Stream<String> matchingLines(Path file, Pattern pattern) {
        try {
            List<String> lines = Files.readAllLines(file);
            return lines.stream()
                    .filter(pattern.asPredicate())
                    .map(line -> ROOT.relativize(file) + ": " + line);
        } catch (IOException | UncheckedIOException e) {
            return Stream.empty();
        }
    }

    private static Path resolve(String relative) {
        Path resolved = ROOT.resolve(relative).normalize();
        if (!resolved.startsWith(ROOT)) {
            throw new IllegalArgumentException("Path escapes project root: " + relative);
        }
        return resolved;
    }

    private static McpSchema.CallToolResult error(Exception e) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(e.getMessage() == null ? e.toString() : e.getMessage())
                .isError(true)
                .build();
    }
}
