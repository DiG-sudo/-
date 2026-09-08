package com.guodi.aikb.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.guodi.aikb.ai.agent.runtime.AgentEvidence;
import com.guodi.aikb.ai.agent.runtime.SourceRef;

class AgentResultTest {

    @Test
    void mergesOverlappingAndAdjacentSuccessfulSourceRanges() {
        AgentEvidence first = new AgentEvidence(
                1, "REACT", "read_source", "{}", "first",
                List.of(new SourceRef("Example.java", 1, 100))
        );
        AgentEvidence second = new AgentEvidence(
                2, "REACT", "read_source", "{}", "second",
                List.of(
                        new SourceRef("Example.java", 80, 150),
                        new SourceRef("Example.java", 151, 160),
                        new SourceRef("Other.java", 5, 9)
                )
        );

        AgentResult result = new AgentResult(
                "answer", "VERIFIED", "supported", List.of(first, second)
        );

        assertThat(result.getSources()).containsExactly(
                new SourceRef("Example.java", 1, 160),
                new SourceRef("Other.java", 5, 9)
        );
    }

    @Test
    void excludesSourcesFromFailedEvidence() {
        AgentEvidence failed = new AgentEvidence(
                1,
                "REACT",
                "read_source",
                "{}",
                "failure",
                List.of(new SourceRef("ShouldNotAppear.java", 1, 2)),
                "FAILED"
        );

        AgentResult result = new AgentResult(
                "answer", "INSUFFICIENT_EVIDENCE", "missing", List.of(failed)
        );

        assertThat(result.getSources()).isEmpty();
    }
}
