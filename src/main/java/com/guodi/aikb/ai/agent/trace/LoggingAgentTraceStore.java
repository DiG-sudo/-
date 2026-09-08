package com.guodi.aikb.ai.agent.trace;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LoggingAgentTraceStore implements AgentTraceStore {

    @Override
    public void saveToolRound(
            Long sessionId,
            Long userMessageId,
            int actionRound,
            String scope,
            String assistantText,
            List<AssistantMessage.ToolCall> toolCalls,
            List<ToolResponseMessage.ToolResponse> toolResponses) {
        List<String> tools = toolCalls == null
                ? List.of()
                : toolCalls.stream().map(AssistantMessage.ToolCall::name).toList();
        int resultChars = toolResponses == null
                ? 0
                : toolResponses.stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .mapToInt(value -> value == null ? 0 : value.length())
                        .sum();
        log.info(
                "[AGENT][TRACE] sessionId={} messageId={} scope={} round={} tools={} responseCount={} resultChars={}",
                sessionId, userMessageId, scope, actionRound, tools,
                toolResponses == null ? 0 : toolResponses.size(), resultChars
        );
    }
}
