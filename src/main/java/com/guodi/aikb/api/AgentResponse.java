package com.guodi.aikb.api;

import java.util.List;

import com.guodi.aikb.ai.agent.AgentResult;
import com.guodi.aikb.ai.agent.runtime.SourceRef;

public record AgentResponse(
        Long sessionId,
        String answer,
        List<SourceRef> sources,
        VerificationView verification,
        List<ToolActivityView> toolActivity) {

    public static AgentResponse from(Long sessionId, AgentResult result) {
        List<ToolActivityView> toolActivity = result.getEvidence().stream()
                .map(item -> new ToolActivityView(
                        item.getTool(),
                        item.getArguments(),
                        item.getStatus()
                ))
                .toList();
        long successfulEvidenceCount = result.getEvidence().stream()
                .filter(item -> item != null && item.isSuccessful())
                .count();
        return new AgentResponse(
                sessionId,
                result.getAnswer(),
                result.getSources(),
                new VerificationView(
                        result.getVerificationStatus(),
                        result.getVerificationReason(),
                        Math.toIntExact(successfulEvidenceCount)
                ),
                toolActivity
        );
    }
}
