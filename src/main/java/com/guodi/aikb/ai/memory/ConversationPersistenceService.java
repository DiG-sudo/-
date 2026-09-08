package com.guodi.aikb.ai.memory;

import com.guodi.aikb.ai.agent.AgentResult;

/** 保存当前Turn的用户消息和最终回答；工具轨迹由AgentTraceStore保存。 */
public interface ConversationPersistenceService {

    Long startTurn(Long sessionId, String userMessage);

    void completeTurn(Long sessionId, AgentResult result);
}
