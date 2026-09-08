package com.guodi.aikb.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.guodi.aikb.ai.agent.AgentResult;
import com.guodi.aikb.ai.agent.AgentService;
import com.guodi.aikb.ai.agent.runtime.AgentEvidence;
import com.guodi.aikb.ai.agent.runtime.SourceRef;

class AgentControllerTest {

    @Test
    void returnsStructuredAnswerSourcesVerificationAndToolActivity() {
        AgentService service = mock(AgentService.class);
        SourceRef source = new SourceRef("src/main/java/Example.java", 10, 20);
        AgentEvidence evidence = new AgentEvidence(
                1, "REACT", "read_source", "{\"path\":\"Example.java\"}",
                "source evidence", List.of(source)
        );
        AgentEvidence failedEvidence = new AgentEvidence(
                2, "REACT", "list_directory", "{\"path\":\"missing\"}", "missing",
                List.of(), "FAILED"
        );
        AgentResult result = new AgentResult(
                "answer", "VERIFIED", "supported", List.of(evidence, failedEvidence)
        );
        when(service.chat("question", 9L, true)).thenReturn(result);

        AgentResponse response = new AgentController(service)
                .analyze(new AgentRequest("question", 9L, true));

        assertThat(response.sessionId()).isEqualTo(9L);
        assertThat(response.answer()).isEqualTo("answer");
        assertThat(response.sources()).containsExactly(source);
        assertThat(response.verification().status()).isEqualTo("VERIFIED");
        assertThat(response.verification().evidenceCount()).isEqualTo(1);
        assertThat(response.toolActivity()).hasSize(2);
        assertThat(response.toolActivity().get(0).status()).isEqualTo("SUCCESS");
        assertThat(response.toolActivity().get(1).status()).isEqualTo("FAILED");
        verify(service).chat("question", 9L, true);
    }
}
