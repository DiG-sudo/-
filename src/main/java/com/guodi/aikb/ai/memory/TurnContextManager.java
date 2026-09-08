package com.guodi.aikb.ai.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * 为新的 User Turn 构造跨轮会话上下文。
 */
public interface TurnContextManager {

    /**
     * 返回已持久化摘要与当前消息之前、尚未摘要的会话历史。
     */
    List<Message> prepareContext(Long sessionId);

    void recordTurn(Long sessionId, String userMessage, String assistantMessage);
}
