package com.guodi.aikb.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.aikb.ai.agent.AgentResult;
import com.guodi.aikb.ai.agent.runtime.AgentRuntime;
import com.guodi.aikb.ai.memory.TurnContextManager;
import com.guodi.aikb.ai.tool.catalog.ToolCatalogService;
import com.guodi.aikb.workspace.service.RepositoryOverviewService;

class AgentServiceImplRepositoryOverviewTest {

    @Test
    void injectsPersistedRepositoryOverviewIntoEveryTurnAndRecordsConversation() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        TurnContextManager turnContextManager = mock(TurnContextManager.class);
        ToolCatalogService toolCatalogService = mock(ToolCatalogService.class);
        RepositoryOverviewService overviewService = mock(RepositoryOverviewService.class);

        when(turnContextManager.prepareContext(any())).thenReturn(List.of());
        when(toolCatalogService.getEnabledCallbacks()).thenReturn(new ToolCallback[0]);
        when(overviewService.getOverview()).thenReturn(
                "repository_root: /repo\noverview_content:\n  - src/"
        );
        when(runtime.execute(any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(new AgentResult("answer", "NOT_REQUESTED", null, List.of()));

        AgentServiceImpl service = new AgentServiceImpl(
                runtime, turnContextManager, toolCatalogService, overviewService
        );

        service.chat("first", 1L, false);
        service.chat("second", 1L, false);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(runtime, times(2)).execute(prompts.capture(), any(), any(), any(), any(Boolean.class));
        verify(overviewService, times(2)).getOverview();
        verify(turnContextManager).recordTurn(1L, "first", "answer");
        verify(turnContextManager).recordTurn(1L, "second", "answer");

        for (Prompt prompt : prompts.getAllValues()) {
            List<Message> messages = prompt.getInstructions();
            assertThat(messages).hasSize(3);
            assertThat(messages.get(0).getText())
                    .contains("历史回答和历史Tool Trace只能作为理解与导航上下文")
                    .contains("同一Turn内不要重复获取已经足够的信息");
            assertThat(messages.get(1).getText())
                    .contains("## Repository Overview")
                    .contains("repository_root: /repo")
                    .contains("navigation context");
        }
    }
}
