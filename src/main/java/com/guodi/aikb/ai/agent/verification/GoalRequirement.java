package com.guodi.aikb.ai.agent.verification;

import java.util.List;

import lombok.Getter;

/** 可复用的不可变验收目标，验证状态只允许 false -> true。 */
@Getter
public final class GoalRequirement {

    private final String id;
    private final String description;
    private final String acceptanceCriteria;
    private boolean verified;
    private List<Integer> evidenceIds = List.of();
    private String verificationReason;
    private String evidenceSummary;
    private boolean auditResultInvalid;
    private String auditValidationError;

    public GoalRequirement(String id, String description, String acceptanceCriteria) {
        if (id == null || id.isBlank()
                || description == null || description.isBlank()
                || acceptanceCriteria == null || acceptanceCriteria.isBlank()) {
            throw new IllegalArgumentException(
                    "Requirement id、description 和 acceptanceCriteria 不能为空"
            );
        }
        this.id = id.trim();
        this.description = description.trim();
        this.acceptanceCriteria = acceptanceCriteria.trim();
    }

    public void verify(List<Integer> evidenceIds, String reason) {
        if (verified) {
            return;
        }
        if (evidenceIds == null || evidenceIds.isEmpty()
                || evidenceIds.stream().anyMatch(id -> id == null || id < 1)
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("验证必须包含 Evidence ID 和原因");
        }
        this.evidenceIds = List.copyOf(evidenceIds);
        this.verificationReason = reason.trim();
        this.evidenceSummary = reason.trim();
        this.auditResultInvalid = false;
        this.auditValidationError = null;
        this.verified = true;
    }

    public void recordGap(String reason) {
        recordGap(List.of(), reason);
    }

    public void recordGap(List<Integer> evidenceIds, String reason) {
        if (!verified && reason != null && !reason.isBlank()) {
            this.evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            this.verificationReason = reason.trim();
            this.evidenceSummary = reason.trim();
            this.auditResultInvalid = false;
            this.auditValidationError = null;
        }
    }

    public void recordInvalidAudit(String reason) {
        if (!verified) {
            this.auditResultInvalid = true;
            this.auditValidationError = reason == null || reason.isBlank()
                    ? "审计器返回了无效结果"
                    : reason.trim();
        }
    }

    public void clearInvalidAudit() {
        if (!verified) {
            this.auditResultInvalid = false;
            this.auditValidationError = null;
        }
    }
}
