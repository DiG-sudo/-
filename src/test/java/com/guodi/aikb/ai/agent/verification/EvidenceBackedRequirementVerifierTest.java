package com.guodi.aikb.ai.agent.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.aikb.ai.agent.runtime.AgentEvidence;
import com.guodi.aikb.ai.agent.runtime.SourceRef;

class EvidenceBackedRequirementVerifierTest {

    @Test
    void acceptsVerifiedResultWithRealMatchingEvidenceId() {
        GoalRequirement requirement = requirement();
        EvidenceBackedRequirementVerifier verifier = verifierReturning(
                "{\"verified\":true,\"evidenceIds\":[1],\"reason\":\"directly supported\"}"
        );

        boolean verified = verifier.evaluate(
                requirement, "answer", List.of(evidence(1, "REACT"))
        );

        assertThat(verified).isTrue();
        assertThat(requirement.isVerified()).isTrue();
        assertThat(requirement.getEvidenceIds()).containsExactly(1);
    }

    @Test
    void rejectsAuditorEvidenceIdThatDoesNotExist() {
        GoalRequirement requirement = requirement();
        EvidenceBackedRequirementVerifier verifier = verifierReturning(
                "{\"verified\":true,\"evidenceIds\":[99],\"reason\":\"claimed support\"}"
        );

        assertThat(verifier.evaluate(
                requirement, "answer", List.of(evidence(1, "REACT"))
        )).isFalse();
        assertThat(requirement.isVerified()).isFalse();
    }

    @Test
    void rejectsEvidenceBelongingToAnotherRequirement() {
        GoalRequirement requirement = requirement();
        EvidenceBackedRequirementVerifier verifier = verifierReturning(
                "{\"verified\":true,\"evidenceIds\":[1],\"reason\":\"claimed support\"}"
        );

        assertThat(verifier.evaluate(
                requirement, "answer", List.of(evidence(1, "OTHER"))
        )).isFalse();
        assertThat(requirement.isVerified()).isFalse();
    }

    @Test
    void auditContextIncludesToolStatusAndSourceRefs() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"verified\":true,\"evidenceIds\":[1],\"reason\":\"supported\"}"
                ))))
        );
        EvidenceBackedRequirementVerifier verifier =
                new EvidenceBackedRequirementVerifier(chatModel);
        AgentEvidence evidence = new AgentEvidence(
                1,
                "REACT",
                "read_source",
                "{\"path\":\"Example.java\"}",
                "1 | class Example {}",
                List.of(new SourceRef("Example.java", 1, 1))
        );

        verifier.evaluate(requirement(), "answer", List.of(evidence));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(promptCaptor.capture());
        String auditInput = promptCaptor.getValue().getInstructions().get(1).getText();
        assertThat(auditInput)
                .contains("Tool: read_source")
                .contains("Status: SUCCESS")
                .contains("SourceRefs: [Example.java:1-1]")
                .contains("1 | class Example {}");
    }

    @Test
    void retriesInvalidEvidenceIdWithoutTurningItIntoARequirementGap() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"verified\":true,\"evidenceIds\":[0],\"reason\":\"invalid id\"}"),
                response("{\"verified\":true,\"evidenceIds\":[1],\"reason\":\"supported\"}")
        );
        GoalRequirement requirement = requirement();

        boolean verified = new EvidenceBackedRequirementVerifier(chatModel).evaluate(
                requirement,
                "answer",
                List.of(evidence(1, "REACT"))
        );

        assertThat(verified).isTrue();
        assertThat(requirement.isAuditResultInvalid()).isFalse();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void marksAuditAsInvalidAfterBoundedRetries() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"verified\":true,\"evidenceIds\":[0],\"reason\":\"invalid id\"}")
        );
        GoalRequirement requirement = requirement();

        boolean verified = new EvidenceBackedRequirementVerifier(chatModel).evaluate(
                requirement,
                "answer",
                List.of(evidence(1, "REACT"))
        );

        assertThat(verified).isFalse();
        assertThat(requirement.isAuditResultInvalid()).isTrue();
        assertThat(requirement.getAuditValidationError()).contains("无效Evidence ID");
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void recordsConcreteUnsupportedConclusionsForDirectedExploration() {
        EvidenceBackedRequirementVerifier verifier = verifierReturning("""
                {
                  "verified": false,
                  "evidenceIds": [1],
                  "reason": "Controller入口已经确认。",
                  "unsupportedConclusions": [
                    {
                      "conclusion": "普通Agent可能动态获得额外MCP工具",
                      "reason": "现有证据没有覆盖ToolCatalog的过滤逻辑",
                      "neededEvidence": "读取ToolCatalogService中MCP回调过滤代码"
                    }
                  ]
                }
                """);
        GoalRequirement requirement = requirement();

        boolean verified = verifier.evaluate(
                requirement, "answer", List.of(evidence(1, "REACT"))
        );

        assertThat(verified).isFalse();
        assertThat(requirement.getVerificationReason())
                .contains("当前已确认部分：Controller入口已经确认")
                .contains("结论：普通Agent可能动态获得额外MCP工具")
                .contains("原因：现有证据没有覆盖ToolCatalog的过滤逻辑")
                .contains("需要补充：读取ToolCatalogService中MCP回调过滤代码");
        assertThat(requirement.getEvidenceIds()).containsExactly(1);
    }

    private EvidenceBackedRequirementVerifier verifierReturning(String json) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(json));
        return new EvidenceBackedRequirementVerifier(chatModel);
    }

    private ChatResponse response(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private GoalRequirement requirement() {
        return new GoalRequirement("REACT", "answer the question", "use direct evidence");
    }

    private AgentEvidence evidence(int id, String requirementId) {
        return new AgentEvidence(
                id, requirementId, "read_source", "{}", "raw result"
        );
    }
}
