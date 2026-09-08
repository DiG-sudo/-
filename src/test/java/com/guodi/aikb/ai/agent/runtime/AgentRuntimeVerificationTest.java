package com.guodi.aikb.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import com.guodi.aikb.ai.agent.AgentResult;
import com.guodi.aikb.ai.agent.trace.AgentTraceStore;
import com.guodi.aikb.ai.agent.verification.GoalRequirement;
import com.guodi.aikb.ai.agent.verification.RequirementVerifier;
import com.guodi.aikb.ai.memory.ReactContextManager;

class AgentRuntimeVerificationTest {

    @Test
    void standardModeSkipsVerifier() {
        Fixture fixture = fixture();

        AgentResult result = fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        );

        assertThat(result.getAnswer()).isEqualTo("answer");
        assertThat(result.getVerificationStatus()).isEqualTo("NOT_REQUESTED");
        verify(fixture.verifier, never()).evaluate(any(), any(), any());
    }

    @Test
    void verifiedModeRunsVerifierAndReturnsRealStatus() {
        Fixture fixture = fixture();
        when(fixture.verifier.evaluate(any(), any(), any())).thenReturn(false);

        AgentResult result = fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                true
        );

        assertThat(result.getVerificationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(fixture.verifier).evaluate(any(), any(), any());
    }

    @Test
    void invalidAuditResultDoesNotTriggerSupplementExploration() {
        Fixture fixture = fixture();
        when(fixture.verifier.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            GoalRequirement requirement = invocation.getArgument(0);
            requirement.recordInvalidAudit("审计器连续返回非法Evidence ID");
            return false;
        });

        AgentResult result = fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                true
        );

        assertThat(result.getAnswer()).isEqualTo("answer");
        assertThat(result.getVerificationStatus()).isEqualTo("VERIFICATION_ERROR");
        assertThat(result.getVerificationReason()).contains("非法Evidence ID");
        verify(fixture.verifier).evaluate(any(), any(), any());
    }

    @Test
    void auditFailureCarriesFeedbackIntoOneToolFreeRevision() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse initialResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("initial-answer"))
        ));
        ChatResponse revisedResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("revised-answer"))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(initialResponse, revisedResponse);

        RequirementVerifier verifier = mock(RequirementVerifier.class);
        AtomicInteger audits = new AtomicInteger();
        when(verifier.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            GoalRequirement requirement = invocation.getArgument(0);
            if (audits.getAndIncrement() == 0) {
                requirement.recordGap("接口总数与源码不一致，需要重新核实。");
                return false;
            }
            return false;
        });

        AgentRuntime runtime = new AgentRuntime(
                chatModel,
                mock(ToolCallingManager.class),
                mock(AgentTraceStore.class),
                mock(ReactContextManager.class),
                verifier
        );
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("question")),
                options
        );

        AgentResult result = runtime.execute(prompt, options, 1L, 2L, true);

        assertThat(result.getAnswer()).isEqualTo("revised-answer");
        assertThat(result.getVerificationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(verifier).evaluate(any(), any(), any());

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions())
                .anyMatch(message -> message.getText().contains("接口总数与源码不一致")
                        && message.getText().contains("initial-answer")
                        && message.getText().contains("原始用户目标"));
    }

    @Test
    void auditRevisionDoesNotCallRepositoryTools() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse initialResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("initial-answer"))
        ));
        ChatResponse revisedResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("revised-answer"))
        ));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(initialResponse, revisedResponse);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);

        RequirementVerifier verifier = mock(RequirementVerifier.class);
        when(verifier.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            GoalRequirement requirement = invocation.getArgument(0);
            requirement.recordGap("结论A没有源码支持，应从答案中删除。");
            return false;
        });

        AgentRuntime runtime = new AgentRuntime(
                chatModel,
                toolCallingManager,
                mock(AgentTraceStore.class),
                mock(ReactContextManager.class),
                verifier
        );
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("question")),
                options
        );

        AgentResult result = runtime.execute(prompt, options, 1L, 2L, true);

        assertThat(result.getAnswer()).isEqualTo("revised-answer");
        assertThat(result.getVerificationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.getEvidence()).isEmpty();
        verify(toolCallingManager, never()).executeToolCalls(any(), any());
        verify(verifier).evaluate(any(), any(), any());
    }

    @Test
    void auditRevisionDoesNotReceiveRawToolContent() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-source", "function", "read_source",
                "{\"path\":\"src/main/java/Example.java\"}"
        );
        String rawMarker = "RAW_SOURCE_CONTENT_MUST_NOT_BE_COPIED";
        String responseData = "[{\"text\":\"source: src/main/java/Example.java\\n"
                + "10 | " + rawMarker + "\"}]";
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        "call-source", "read_source", responseData
                ))
        );
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(toolCallMessage))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("initial-answer")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("revised-answer"))))
        );
        AtomicInteger audits = new AtomicInteger();
        when(fixture.verifier.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            GoalRequirement requirement = invocation.getArgument(0);
            if (audits.getAndIncrement() == 0) {
                requirement.recordGap(List.of(1), "结论A缺少精确方法范围，需要定向核实。");
                return false;
            }
            requirement.verify(List.of(1), "结论均已获得支持。");
            return true;
        });

        AgentResult result = fixture.runtime.execute(
                fixture.prompt, fixture.options, 1L, 2L, true
        );

        assertThat(result.getVerificationStatus()).isEqualTo("PARTIALLY_VERIFIED");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(3)).call(prompts.capture());
        String supplementInput = prompts.getAllValues().get(2).getInstructions().stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(supplementInput)
                .contains("结论A缺少精确方法范围")
                .contains("initial-answer")
                .doesNotContain(rawMarker);
    }

    @Test
    void evidenceMatchesToolCallsByIdWhenResponsesAreReversed() {
        AssistantMessage.ToolCall firstCall = new AssistantMessage.ToolCall(
                "call-1", "function", "first_tool", "{\"path\":\"first\"}"
        );
        AssistantMessage.ToolCall secondCall = new AssistantMessage.ToolCall(
                "call-2", "function", "second_tool", "{\"path\":\"second\"}"
        );
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(firstCall, secondCall),
                List.of(
                        new ToolResponseMessage.ToolResponse(
                                "call-2", "second_tool", "second-result"
                        ),
                        new ToolResponseMessage.ToolResponse(
                                "call-1", "first_tool", "first-result"
                        )
                )
        );

        AgentResult result = fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        );

        assertThat(result.getEvidence()).hasSize(2);
        assertThat(result.getEvidence().get(0).getTool()).isEqualTo("first_tool");
        assertThat(result.getEvidence().get(0).getArguments())
                .isEqualTo("{\"path\":\"first\"}");
        assertThat(result.getEvidence().get(0).getContent())
                .contains("first-result")
                .doesNotContain("second-result");
        assertThat(result.getEvidence().get(1).getTool()).isEqualTo("second_tool");
        assertThat(result.getEvidence().get(1).getArguments())
                .isEqualTo("{\"path\":\"second\"}");
        assertThat(result.getEvidence().get(1).getContent())
                .contains("second-result")
                .doesNotContain("first-result");
        verify(fixture.toolCallingManager, times(1))
                .executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        verify(fixture.chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void rejectsToolResponseWithUnknownCallId() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "first_tool", "{}"
        );
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        "unknown-call", "first_tool", "result"
                ))
        );

        assertThatThrownBy(() -> fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知ToolCall ID")
                .hasMessageContaining("unknown-call");
    }

    @Test
    void rejectsToolResponseWithoutCallId() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "first_tool", "{}"
        );
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        null, "first_tool", "result"
                ))
        );

        assertThatThrownBy(() -> fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ToolResponse缺少有效ID");
    }

    @Test
    void rejectsMissingToolResponse() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "first_tool", "{}"
        );
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of()
        );

        assertThatThrownBy(() -> fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ToolResponse缺少对应ToolCall ID")
                .hasMessageContaining("call-1");
    }

    @Test
    void invalidToolAddsRuntimeFeedbackAndCanThenFinalize() {
        ToolRoundFixture fixture = invalidToolFixture(true);

        AgentResult result = fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        );

        assertThat(result.getAnswer()).isEqualTo("answer-after-feedback");
        verify(fixture.toolCallingManager, never())
                .executeToolCalls(any(Prompt.class), any(ChatResponse.class));

        ArgumentCaptor<Prompt> promptCaptor =
                ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(2)).call(promptCaptor.capture());
        Prompt feedbackPrompt = promptCaptor.getAllValues().get(1);
        assertThat(feedbackPrompt.getInstructions()).anyMatch(message ->
                message instanceof SystemMessage
                        && message.getText().contains("unavailable_tool")
                        && message.getText().contains("available_tool")
        );
    }

    @Test
    void invalidToolFailsAfterBoundedRetriesWithoutExecution() {
        ToolRoundFixture fixture = invalidToolFixture(false);

        assertThatThrownBy(() -> fixture.runtime.execute(
                fixture.prompt,
                fixture.options,
                1L,
                2L,
                false
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连续调用当前不可用Tool")
                .hasMessageContaining("unavailable_tool");

        verify(fixture.chatModel, times(3)).call(any(Prompt.class));
        verify(fixture.toolCallingManager, never())
                .executeToolCalls(any(Prompt.class), any(ChatResponse.class));
    }

    @Test
    void readSourceUsesActualReturnedRangeAndPreservesRawEvidence() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-source", "function", "read_source",
                "{\"path\":\"src/main/java/Example.java\",\"start_line\":120,\"max_lines\":80}"
        );
        String responseData = """
                [{"text":"source: src/main/java/Example.java\\n120 | class Example {\\n121 | }\\n122 | "}]
                """.trim();
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        "call-source", "read_source", responseData
                ))
        );

        AgentResult result = fixture.runtime.execute(
                fixture.prompt, fixture.options, 1L, 2L, false
        );

        assertThat(result.getSources()).containsExactly(
                new SourceRef("src/main/java/Example.java", 120, 122)
        );
        assertThat(result.getEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getSourceRefs()).isEqualTo(result.getSources());
            assertThat(evidence.getContent()).contains(responseData);
        });
    }

    @Test
    void malformedReadSourceResponseKeepsRawEvidenceWithoutSourceRef() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-source", "function", "read_source",
                "{\"path\":\"src/main/java/Example.java\"}"
        );
        String responseData = "not-mcp-content-json";
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        "call-source", "read_source", responseData
                ))
        );

        AgentResult result = fixture.runtime.execute(
                fixture.prompt, fixture.options, 1L, 2L, false
        );

        assertThat(result.getSources()).isEmpty();
        assertThat(result.getEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getSourceRefs()).isEmpty();
            assertThat(evidence.getContent()).contains(responseData);
        });
    }

    @Test
    void verifiedAuditExcludesFailedToolResults() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-source", "function", "read_source",
                "{\"path\":\"Missing.java\"}"
        );
        ToolRoundFixture fixture = toolRoundFixture(
                List.of(toolCall),
                List.of(new ToolResponseMessage.ToolResponse(
                        "call-source",
                        "read_source",
                        "[AIKB_TOOL_ERROR] missing file"
                ))
        );
        when(fixture.verifier.evaluate(any(), any(), any())).thenReturn(false);

        AgentResult result = fixture.runtime.execute(
                fixture.prompt, fixture.options, 1L, 2L, true
        );

        assertThat(result.getEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getStatus()).isEqualTo("FAILED");
            assertThat(evidence.getSourceRefs()).isEmpty();
        });
        verify(fixture.verifier).evaluate(
                any(),
                any(),
                org.mockito.ArgumentMatchers.argThat(List::isEmpty)
        );
    }

    private Fixture fixture() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("answer"))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        RequirementVerifier verifier = mock(RequirementVerifier.class);
        AgentRuntime runtime = new AgentRuntime(
                chatModel,
                mock(ToolCallingManager.class),
                mock(AgentTraceStore.class),
                mock(ReactContextManager.class),
                verifier
        );
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("question")),
                options
        );
        return new Fixture(runtime, verifier, options, prompt);
    }

    private ToolRoundFixture toolRoundFixture(
            List<AssistantMessage.ToolCall> toolCalls,
            List<ToolResponseMessage.ToolResponse> toolResponses) {

        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        ChatResponse toolCallResponse = new ChatResponse(List.of(
                new Generation(toolCallMessage)
        ));
        ChatResponse finalResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("answer"))
        ));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse, finalResponse);

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(toolResponses)
                .build();
        List<Message> toolHistory = List.of(
                new SystemMessage("system"),
                new UserMessage("react"),
                toolCallMessage,
                toolResponseMessage
        );
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(toolHistory)
                        .build());

        ReactContextManager reactContextManager = mock(ReactContextManager.class);
        when(reactContextManager.prepareNextContext(
                any(), anyInt(), anyInt(), any()
        )).thenReturn(toolHistory);

        RequirementVerifier verifier = mock(RequirementVerifier.class);
        AgentRuntime runtime = new AgentRuntime(
                chatModel,
                toolCallingManager,
                mock(AgentTraceStore.class),
                reactContextManager,
                verifier
        );
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolNames(toolCalls.stream()
                        .map(AssistantMessage.ToolCall::name)
                        .toArray(String[]::new))
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("question")),
                options
        );
        return new ToolRoundFixture(
                runtime,
                options,
                prompt,
                chatModel,
                toolCallingManager,
                verifier
        );
    }

    private ToolRoundFixture invalidToolFixture(boolean eventuallyFinalizes) {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage invalidToolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "invalid-call",
                        "function",
                        "unavailable_tool",
                        "{}"
                )))
                .build();
        ChatResponse invalidToolResponse = new ChatResponse(List.of(
                new Generation(invalidToolMessage)
        ));
        if (eventuallyFinalizes) {
            ChatResponse finalResponse = new ChatResponse(List.of(
                    new Generation(new AssistantMessage("answer-after-feedback"))
            ));
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(invalidToolResponse, finalResponse);
        }
        else {
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(invalidToolResponse);
        }

        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        RequirementVerifier verifier = mock(RequirementVerifier.class);
        AgentRuntime runtime = new AgentRuntime(
                chatModel,
                toolCallingManager,
                mock(AgentTraceStore.class),
                mock(ReactContextManager.class),
                verifier
        );
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolNames("available_tool")
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("question")),
                options
        );
        return new ToolRoundFixture(
                runtime,
                options,
                prompt,
                chatModel,
                toolCallingManager,
                verifier
        );
    }

    private static class Fixture {
        private final AgentRuntime runtime;
        private final RequirementVerifier verifier;
        private final ToolCallingChatOptions options;
        private final Prompt prompt;

        private Fixture(
                AgentRuntime runtime,
                RequirementVerifier verifier,
                ToolCallingChatOptions options,
                Prompt prompt) {
            this.runtime = runtime;
            this.verifier = verifier;
            this.options = options;
            this.prompt = prompt;
        }
    }

    private static class ToolRoundFixture {
        private final AgentRuntime runtime;
        private final ToolCallingChatOptions options;
        private final Prompt prompt;
        private final ChatModel chatModel;
        private final ToolCallingManager toolCallingManager;
        private final RequirementVerifier verifier;

        private ToolRoundFixture(
                AgentRuntime runtime,
                ToolCallingChatOptions options,
                Prompt prompt,
                ChatModel chatModel,
                ToolCallingManager toolCallingManager,
                RequirementVerifier verifier) {
            this.runtime = runtime;
            this.options = options;
            this.prompt = prompt;
            this.chatModel = chatModel;
            this.toolCallingManager = toolCallingManager;
            this.verifier = verifier;
        }
    }
}
