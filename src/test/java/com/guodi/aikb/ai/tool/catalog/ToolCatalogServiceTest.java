package com.guodi.aikb.ai.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import com.guodi.aikb.ai.tool.ToolExecutionStatus;

class ToolCatalogServiceTest {

    @Test
    void exposesExactlyThreeRepositoryToolsAndKeepsRepoMapInternal() {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        ToolCallback[] callbacks = new ToolCallback[]{
                callback("search_repository"), callback("read_source"),
                callback("list_directory"), callback("repo_map"), callback("unknown_tool")
        };
        when(provider.getToolCallbacks()).thenReturn(callbacks);

        ToolCatalogService service = newService(provider);

        assertThat(service.getEnabledCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("search_repository", "read_source", "list_directory");
        assertThat(service.listTools())
                .extracting(ToolDescriptor::getName)
                .doesNotContain("repo_map", "unknown_tool");
        assertThat(descriptor(service, "search_repository").getDescription())
                .contains("定义、引用、配置、文档或任意已知文本");
    }

    @Test
    void disabledToolIsListedButNotExposedToAgent() {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        ToolCallback readSource = callback("read_source");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{readSource});
        ToolCatalogService service = newService(provider);

        service.setEnabled("read_source", false);

        assertThat(descriptor(service, "read_source").isEnabled()).isFalse();
        assertThat(service.getEnabledCallbacks()).isEmpty();
    }

    @Test
    void wrapsToolExceptionsInBackendFailureStatus() {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        ToolCallback failing = callback("read_source");
        when(failing.call("{}"))
                .thenThrow(new IllegalStateException("missing file"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{failing});

        ToolCallback exposed = newService(provider).getEnabledCallbacks()[0];
        String result = exposed.call("{}");

        assertThat(result).startsWith("[AIKB_TOOL_ERROR]").contains("missing file");
        assertThat(ToolExecutionStatus.isFailure(result)).isTrue();
    }

    private static ToolCatalogService newService(SyncMcpToolCallbackProvider provider) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> holder = mock(ObjectProvider.class);
        when(holder.getIfAvailable()).thenReturn(provider);
        return new ToolCatalogService(holder);
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description("original description").inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static ToolDescriptor descriptor(ToolCatalogService service, String name) {
        return service.listTools().stream()
                .filter(item -> item.getName().equals(name))
                .findFirst().orElseThrow();
    }
}
