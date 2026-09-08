package com.guodi.aikb.ai.agent.trace;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 持久化当前 User Turn 内的 Agent 工具执行轨迹。
 */
public interface AgentTraceStore {

    void saveToolRound(
            Long sessionId,
            Long userMessageId,
            int actionRound,
            String scope,
            String assistantText,
            List<AssistantMessage.ToolCall> toolCalls,
            List<ToolResponseMessage.ToolResponse> toolResponses
    );
}
