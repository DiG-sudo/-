package com.guodi.aikb.ai.tool.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.guodi.aikb.ai.tool.ToolExecutionStatus;

@Service
public class ToolCatalogService {

    private static final String REPOSITORY_INITIALIZATION_TOOL = "repo_map";
    private static final Set<String> AGENT_REPOSITORY_TOOLS = Set.of(
            "search_repository",
            "read_source",
            "list_directory"
    );

    private static final Map<String, String> MCP_DESCRIPTIONS = Map.of(
            "search_repository", "在当前仓库的可搜索文本文件内容中查找字面量，返回匹配路径、"
                    + "行号和上下文。适合定位定义、引用、配置、文档或任意已知文本；"
                    + "已知子目录时使用path缩小范围。它不按文件名搜索，重要匹配应继续用read_source核实。",
            "read_source", "读取当前仓库内一个已确认的文本文件，返回带真实行号的内容。"
                    + "已知路径时可直接使用；通过start_line和max_lines读取必要范围，"
                    + "避免重复或重叠读取整个大文件。路径未知时先使用search_repository定位。",
            "list_directory", "列出当前仓库内指定目录的一层直接子项，不递归，也不读取文件内容。"
                    + "适合确认目录结构、路径和相邻文件；查找文件内容时使用search_repository。"
    );

    private final SyncMcpToolCallbackProvider mcpProvider;
    private final Set<String> disabledTools = ConcurrentHashMap.newKeySet();

    public ToolCatalogService(
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        this.mcpProvider = mcpProvider.getIfAvailable();
    }

    public ToolCallback[] getEnabledCallbacks() {
        return allCallbacks().stream()
                .filter(callback -> !disabledTools.contains(callback.getToolDefinition().name()))
                .map(this::withFailureStatus)
                .toArray(ToolCallback[]::new);
    }

    public List<ToolDescriptor> listTools() {
        List<ToolDescriptor> result = new ArrayList<>();
        for (ToolCallback callback : mcpCallbacks()) {
            result.add(toDescriptor(callback, "MCP", "repository-search"));
        }
        return result;
    }

    public void setEnabled(String name, boolean enabled) {
        boolean exists = allCallbacks().stream()
                .anyMatch(callback -> callback.getToolDefinition().name().equals(name));
        if (!exists) {
            throw new IllegalArgumentException("Tool不存在: " + name);
        }
        if (enabled) {
            disabledTools.remove(name);
        } else {
            disabledTools.add(name);
        }
    }

    private List<ToolCallback> allCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.addAll(mcpCallbacks());
        return callbacks;
    }

    private List<ToolCallback> mcpCallbacks() {
        if (mcpProvider == null) {
            return List.of();
        }
        ToolCallback[] callbacks = mcpProvider.getToolCallbacks();
        List<ToolCallback> result = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (REPOSITORY_INITIALIZATION_TOOL.equals(name)
                    || !AGENT_REPOSITORY_TOOLS.contains(name)) {
                continue;
            }
            result.add(overrideDescription(callback));
        }
        return result;
    }

    private ToolDescriptor toDescriptor(ToolCallback callback, String kind, String server) {
        ToolDescriptor descriptor = new ToolDescriptor();
        descriptor.setName(callback.getToolDefinition().name());
        descriptor.setDescription(callback.getToolDefinition().description());
        descriptor.setKind(kind);
        descriptor.setServer(server);
        descriptor.setStatus("AVAILABLE");
        descriptor.setEnabled(!disabledTools.contains(descriptor.getName()));
        return descriptor;
    }

    private ToolCallback overrideDescription(ToolCallback delegate) {
        ToolDefinition original = delegate.getToolDefinition();
        String description = MCP_DESCRIPTIONS.get(original.name());
        if (description == null) {
            return delegate;
        }
        ToolDefinition definition = ToolDefinition.builder()
                .name(original.name())
                .description(description)
                .inputSchema(original.inputSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String input) {
                return delegate.call(input);
            }

            @Override
            public String call(String input, ToolContext context) {
                return delegate.call(input, context);
            }
        };
    }

    private ToolCallback withFailureStatus(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String input) {
                try {
                    return delegate.call(input);
                } catch (RuntimeException exception) {
                    return ToolExecutionStatus.failureResult(exception);
                }
            }

            @Override
            public String call(String input, ToolContext context) {
                try {
                    return delegate.call(input, context);
                } catch (RuntimeException exception) {
                    return ToolExecutionStatus.failureResult(exception);
                }
            }
        };
    }
}
