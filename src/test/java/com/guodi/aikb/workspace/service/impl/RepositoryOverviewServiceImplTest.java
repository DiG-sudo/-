package com.guodi.aikb.workspace.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

class RepositoryOverviewServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsFileCachedOverviewWithoutCallingRepoMapAgain() {
        ToolCallback firstCallback = repoMapCallback("saved overview");
        assertThat(service(firstCallback).getOverview()).isEqualTo("saved overview");

        ToolCallback secondCallback = repoMapCallback("new overview");
        assertThat(service(secondCallback).getOverview()).isEqualTo("saved overview");
        verify(secondCallback, never()).call(any(String.class));
    }

    @Test
    void generatesAndPersistsOverviewOnFirstAccess() {
        ToolCallback callback = repoMapCallback("generated overview");
        RepositoryOverviewServiceImpl service = service(callback);

        assertThat(service.getOverview()).isEqualTo("generated overview");
        ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
        verify(callback).call(arguments.capture());
        assertThat(arguments.getValue())
                .contains("\"max_depth\":5")
                .contains("\"max_entries\":300");
        assertThat(temporaryDirectory.resolve("cache")).isDirectory();
    }

    @Test
    void refreshRegeneratesAndOverwritesExistingOverview() {
        ToolCallback callback = repoMapCallback("old overview");
        when(callback.call(any(String.class))).thenReturn("old overview", "fresh overview");
        RepositoryOverviewServiceImpl service = service(callback);

        assertThat(service.getOverview()).isEqualTo("old overview");
        assertThat(service.refreshOverview()).isEqualTo("fresh overview");
        assertThat(service.getOverview()).isEqualTo("fresh overview");
        verify(callback, times(2)).call(any(String.class));
    }

    @SuppressWarnings("unchecked")
    private RepositoryOverviewServiceImpl service(ToolCallback callback) {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{callback});
        ObjectProvider<SyncMcpToolCallbackProvider> holder = mock(ObjectProvider.class);
        when(holder.getIfAvailable()).thenReturn(provider);
        return new RepositoryOverviewServiceImpl(
                holder,
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.resolve("repository").toString(),
                temporaryDirectory.resolve("cache").toString()
        );
    }

    private ToolCallback repoMapCallback(String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("repo_map");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any(String.class))).thenReturn(result);
        return callback;
    }
}
