package com.guodi.aikb.ai.mcp;

import java.util.Set;

import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema;

@Component
public class RepositoryMcpToolFilter implements McpToolFilter {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "search_repository",
            "read_source",
            "list_directory",
            "repo_map"
    );

    @Override
    public boolean test(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        return ALLOWED_TOOLS.contains(tool.name());
    }
}
