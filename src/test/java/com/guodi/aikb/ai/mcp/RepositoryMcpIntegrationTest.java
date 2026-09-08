package com.guodi.aikb.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

class RepositoryMcpIntegrationTest {

    @Test
    void exposesOnlyTheRepositoryAnalysisToolsThroughSpringAiCallbacks() {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path serverScript = projectRoot.resolve("mcp/repository-search-server.mjs");

        ServerParameters parameters = ServerParameters.builder("node")
                .args(serverScript.toString(), projectRoot.toString())
                .build();

        StdioClientTransport transport = new StdioClientTransport(
                parameters,
                McpJsonDefaults.getMapper()
        );

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {

            client.initialize();

            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(
                    new RepositoryMcpToolFilter(),
                    List.of(client)
            );

            List<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                    .map(ToolCallback::getToolDefinition)
                    .map(definition -> definition.name())
                    .toList();

            assertEquals(List.of(
                    "search_repository",
                    "read_source",
                    "list_directory",
                    "repo_map"
            ), toolNames);
            assertFalse(toolNames.contains("directory_tree"));

            ToolCallback readSourceCallback = Arrays.stream(provider.getToolCallbacks())
                    .filter(callback -> "read_source".equals(
                            callback.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow();
            String callbackResult = readSourceCallback.call("""
                    {"path":"src/main/java/com/guodi/aikb/RepositoryAnalysisAgentApplication.java","start_line":1,"max_lines":20}
                    """.trim());
            assertTrue(callbackResult.startsWith("["));
            assertTrue(callbackResult.contains(
                    "source: src/main/java/com/guodi/aikb/RepositoryAnalysisAgentApplication.java"
            ));

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "search_repository",
                            Map.of(
                                    "query", "class AgentRuntime",
                                    "path", "src/main/java",
                                    "maxResults", 20
                            )
                    )
            );

            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertFalse(result.content().isEmpty());
            McpSchema.TextContent content = assertInstanceOf(
                    McpSchema.TextContent.class,
                    result.content().getFirst()
            );
            assertTrue(content.text().contains("AgentRuntime.java"));
            McpSchema.CallToolResult source = client.callTool(new McpSchema.CallToolRequest(
                    "read_source", Map.of(
                            "path", "src/main/java/com/guodi/aikb/RepositoryAnalysisAgentApplication.java",
                            "start_line", 1,
                            "max_lines", 20
                    )));
            assertFalse(Boolean.TRUE.equals(source.isError()));
            assertTrue(((McpSchema.TextContent) source.content().getFirst()).text()
                    .contains("SpringApplication.run"));
            McpSchema.CallToolResult directories = client.callTool(new McpSchema.CallToolRequest(
                    "list_directory", Map.of("path", "src/main", "max_entries", 20)));
            assertFalse(Boolean.TRUE.equals(directories.isError()));
            String listing = ((McpSchema.TextContent) directories.content().getFirst()).text();
            assertTrue(listing.contains("directory: java/"));
            assertFalse(listing.contains(".java"));
            McpSchema.CallToolResult overview = client.callTool(new McpSchema.CallToolRequest(
                    "repo_map", Map.of("max_depth", 5, "max_entries", 300)));
            assertFalse(Boolean.TRUE.equals(overview.isError()));
            String overviewText = ((McpSchema.TextContent) overview.content().getFirst()).text();
            assertTrue(overviewText.contains("repository_root:"));
            assertTrue(overviewText.contains("pom.xml"));
            assertFalse(new RepositoryMcpToolFilter().test(null,
                    McpSchema.Tool.builder().name("directory_tree").build()));
        }
    }
}
