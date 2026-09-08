package com.guodi.aikb.ai.agent.verification;

import java.util.List;

import com.guodi.aikb.ai.agent.runtime.AgentEvidence;

/**
 * Evidence-backed Requirement 验证能力的独立入口。
 *
 * 当前只暴露能力，不由 AgentRuntime 自动调用。
 */
public interface RequirementVerifier {

    /**
     * 根据候选答案和 Evidence 验证 Requirement；成功时只允许 false -> true。
     */
    boolean evaluate(
            GoalRequirement requirement,
            String candidateAnswer,
            List<AgentEvidence> evidence
    );

    /**
     * 生成已确认事实与剩余缺口摘要，不改变 Requirement 的 verified 状态。
     */
    String summarizeCoverage(
            GoalRequirement requirement,
            List<AgentEvidence> evidence
    );
}
