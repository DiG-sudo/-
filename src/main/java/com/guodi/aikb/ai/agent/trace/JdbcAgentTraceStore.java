package com.guodi.aikb.ai.agent.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.aikb.ai.tool.ToolExecutionStatus;

/** 按原始消息协议完整保存每一轮ToolCall和原始ToolResponse。 */
@Component
public class JdbcAgentTraceStore implements AgentTraceStore {

    private static final String ROLE_ASSISTANT_TOOL_CALL = "assistant_tool_call";
    private static final String ROLE_TOOL_RESPONSE = "tool_response";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAgentTraceStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void saveToolRound(
            Long sessionId,
            Long userMessageId,
            int actionRound,
            String scope,
            String assistantText,
            List<AssistantMessage.ToolCall> toolCalls,
            List<ToolResponseMessage.ToolResponse> toolResponses) {

        List<AssistantMessage.ToolCall> calls = toolCalls == null ? List.of() : toolCalls;
        List<ToolResponseMessage.ToolResponse> responses =
                toolResponses == null ? List.of() : toolResponses;

        Map<String, String> statusesByCallId = responses.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ToolResponseMessage.ToolResponse::id,
                        response -> ToolExecutionStatus.fromResult(response.responseData()),
                        (left, right) -> right
                ));

        Map<String, Object> toolCallMessage = baseMessage(
                "assistant_tool_call", userMessageId, actionRound, scope
        );
        toolCallMessage.put("text", assistantText == null ? "" : assistantText);
        toolCallMessage.put("toolCalls", calls.stream().map(call -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", call.id());
            value.put("type", call.type());
            value.put("name", call.name());
            value.put("arguments", call.arguments());
            value.put("status", statusesByCallId.getOrDefault(call.id(), ToolExecutionStatus.FAILED));
            return value;
        }).toList());

        Map<String, Object> toolResponseMessage = baseMessage(
                "tool_response", userMessageId, actionRound, scope
        );
        toolResponseMessage.put("responses", responses.stream().map(response -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", response.id());
            value.put("name", response.name());
            value.put("responseData", response.responseData());
            value.put("status", ToolExecutionStatus.fromResult(response.responseData()));
            return value;
        }).toList());

        insertMessage(sessionId, ROLE_ASSISTANT_TOOL_CALL, toolCallMessage);
        insertMessage(sessionId, ROLE_TOOL_RESPONSE, toolResponseMessage);
    }

    private Map<String, Object> baseMessage(
            String type, Long userMessageId, int actionRound, String scope) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("userMessageId", userMessageId);
        message.put("actionRound", actionRound);
        message.put("scope", scope);
        return message;
    }

    private void insertMessage(Long sessionId, String role, Map<String, Object> content) {
        jdbcTemplate.update(
                "INSERT INTO repository_agent_message (session_id, role, content) VALUES (?, ?, ?)",
                sessionId,
                role,
                toJson(content)
        );
    }

    private String toJson(Map<String, Object> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool执行轨迹序列化失败", exception);
        }
    }
}
