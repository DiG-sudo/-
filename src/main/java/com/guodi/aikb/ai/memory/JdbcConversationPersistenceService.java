package com.guodi.aikb.ai.memory;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.aikb.ai.agent.AgentResult;

@Service
public class JdbcConversationPersistenceService implements ConversationPersistenceService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcConversationPersistenceService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Long startTurn(Long sessionId, String userMessage) {
        if (sessionId == null || sessionId < 1) {
            throw new IllegalArgumentException("sessionId必须为正整数");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("用户消息不能为空");
        }

        jdbcTemplate.update(
                "INSERT IGNORE INTO repository_agent_session (id) VALUES (?)",
                sessionId
        );

        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO repository_agent_message (session_id, role, content) VALUES (?, ?, ?)",
                    new String[]{"id"}
            );
            statement.setLong(1, sessionId);
            statement.setString(2, ROLE_USER);
            statement.setString(3, userMessage);
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (inserted != 1 || key == null) {
            throw new IllegalStateException("保存用户消息失败，messageId未回填");
        }
        return key.longValue();
    }

    @Override
    @Transactional
    public void completeTurn(Long sessionId, AgentResult result) {
        if (sessionId == null || sessionId < 1) {
            throw new IllegalArgumentException("sessionId必须为正整数");
        }
        if (result == null || result.getAnswer() == null || result.getAnswer().isBlank()) {
            throw new IllegalArgumentException("Agent最终回答不能为空");
        }

        jdbcTemplate.update(
                """
                INSERT INTO repository_agent_message
                    (session_id, role, content, repository_source_refs,
                     verification_status, verification_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                ROLE_ASSISTANT,
                result.getAnswer(),
                toJson(result.getSources()),
                result.getVerificationStatus(),
                result.getVerificationReason()
        );
        jdbcTemplate.update(
                "UPDATE repository_agent_session SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                sessionId
        );
    }

    private String toJson(List<?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("源码引用序列化失败", exception);
        }
    }
}
