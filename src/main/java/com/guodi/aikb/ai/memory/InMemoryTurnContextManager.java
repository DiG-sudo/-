package com.guodi.aikb.ai.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
public class InMemoryTurnContextManager implements TurnContextManager {

    private static final int MAX_MESSAGES_PER_SESSION = 20;
    private final ConcurrentMap<Long, Deque<Message>> sessions = new ConcurrentHashMap<>();

    @Override
    public List<Message> prepareContext(Long sessionId) {
        validateSessionId(sessionId);
        Deque<Message> messages = sessions.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    @Override
    public void recordTurn(Long sessionId, String userMessage, String assistantMessage) {
        validateSessionId(sessionId);
        if (userMessage == null || userMessage.isBlank()
                || assistantMessage == null || assistantMessage.isBlank()) {
            throw new IllegalArgumentException("会话消息不能为空");
        }
        Deque<Message> messages = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(new UserMessage(userMessage));
            messages.addLast(new AssistantMessage(assistantMessage));
            while (messages.size() > MAX_MESSAGES_PER_SESSION) {
                messages.removeFirst();
            }
        }
    }

    public void clear(Long sessionId) {
        validateSessionId(sessionId);
        sessions.remove(sessionId);
    }

    private void validateSessionId(Long sessionId) {
        if (sessionId == null || sessionId < 1) {
            throw new IllegalArgumentException("sessionId必须为正整数");
        }
    }
}
