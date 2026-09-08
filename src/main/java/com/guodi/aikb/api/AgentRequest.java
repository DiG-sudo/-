package com.guodi.aikb.api;

public record AgentRequest(
        String question,
        Long sessionId,
        Boolean verified) {
}
