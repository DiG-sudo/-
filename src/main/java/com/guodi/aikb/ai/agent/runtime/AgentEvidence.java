package com.guodi.aikb.ai.agent.runtime;

import java.util.List;

import lombok.Getter;

import com.guodi.aikb.ai.tool.ToolExecutionStatus;

@Getter
public final class AgentEvidence {

    private final int id;
    private final String requirementId;
    private final String tool;
    private final String arguments;
    private final String content;
    private final List<SourceRef> sourceRefs;
    private final String status;

    public AgentEvidence(int id, String requirementId, String tool,
                         String arguments, String content) {
        this(id, requirementId, tool, arguments, content, List.of(), ToolExecutionStatus.SUCCESS);
    }

    public AgentEvidence(int id, String requirementId, String tool,
                         String arguments, String content,
                         List<SourceRef> sourceRefs) {
        this(id, requirementId, tool, arguments, content, sourceRefs, ToolExecutionStatus.SUCCESS);
    }

    public AgentEvidence(int id, String requirementId, String tool,
                         String arguments, String content,
                         List<SourceRef> sourceRefs, String status) {
        if (id < 1 || requirementId == null || requirementId.isBlank()
                || tool == null || tool.isBlank()
                || arguments == null || content == null || content.isBlank()
                || (!ToolExecutionStatus.SUCCESS.equals(status)
                && !ToolExecutionStatus.FAILED.equals(status))) {
            throw new IllegalArgumentException(
                    "Evidence metadata and content must be valid"
            );
        }
        this.id = id;
        this.requirementId = requirementId;
        this.tool = tool;
        this.arguments = arguments;
        this.content = content;
        this.sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        this.status = status;
    }

    public boolean isSuccessful() {
        return ToolExecutionStatus.SUCCESS.equals(status);
    }
}
