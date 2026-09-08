package com.guodi.aikb.ai.agent;

public interface AgentService {

    AgentResult chat(
            String question,
            Long sessionId,
            boolean verifiedMode
    );
}
