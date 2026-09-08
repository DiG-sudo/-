package com.guodi.aikb.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ReactContextManagerTest {

    private static final String OMITTED_TOOL_RESULT =
            "[Earlier tool result omitted]";

    @Test
    void summarizesSingleLargeResultWithoutMutatingRawHistory() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("focused summary"));
        ReactContextManager manager = new ReactContextManager(chatModel);
        String rawResult = "raw-evidence-" + "x".repeat(12_100);
        ToolResponseMessage rawToolMessage = toolMessage(
                new ToolResponseMessage.ToolResponse(
                        "call-large",
                        "read_source",
                        rawResult
                )
        );
        List<Message> rawHistory = List.of(
                new UserMessage("inspect repository"),
                rawToolMessage
        );

        List<Message> visible = manager.prepareNextContext(
                rawHistory,
                1,
                1,
                "inspect repository"
        );

        ToolResponseMessage visibleToolMessage = (ToolResponseMessage) visible.get(1);
        assertThat(visibleToolMessage.getResponses().get(0).responseData())
                .startsWith("[Large tool result summarized]\n")
                .contains("focused summary")
                .doesNotContain(rawResult);
        assertThat(rawHistory).containsExactly(
                rawHistory.get(0),
                rawToolMessage
        );
        assertThat(rawToolMessage.getResponses().get(0).responseData())
                .isEqualTo(rawResult);
        assertThat(visibleToolMessage).isNotSameAs(rawToolMessage);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void omitsOldestBatchWhenTotalToolContextExceedsBudget() {
        ChatModel chatModel = mock(ChatModel.class);
        ReactContextManager manager = new ReactContextManager(chatModel);
        ToolResponseMessage oldest = toolMessage(response("call-oldest", "o".repeat(9_000)));
        ToolResponseMessage middle = toolMessage(response("call-middle", "m".repeat(9_000)));
        ToolResponseMessage newest = toolMessage(response("call-newest", "n".repeat(9_000)));
        List<Message> rawHistory = List.of(
                new UserMessage("goal"),
                oldest,
                middle,
                newest
        );

        List<Message> visible = manager.prepareNextContext(
                rawHistory,
                1,
                3,
                "goal"
        );

        assertThat(responseData(visible, 1)).isEqualTo(OMITTED_TOOL_RESULT);
        assertThat(responseData(visible, 2)).isEqualTo("m".repeat(9_000));
        assertThat(responseData(visible, 3)).isEqualTo("n".repeat(9_000));
        assertThat(oldest.getResponses().get(0).responseData())
                .isEqualTo("o".repeat(9_000));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void retainsNewestBatchEvenWhenBatchAloneExceedsBudget() {
        ChatModel chatModel = mock(ChatModel.class);
        ReactContextManager manager = new ReactContextManager(chatModel);
        ToolResponseMessage older = toolMessage(response("call-older", "old"));
        ToolResponseMessage newest = toolMessage(
                response("call-new-1", "a".repeat(9_000)),
                response("call-new-2", "b".repeat(9_000)),
                response("call-new-3", "c".repeat(9_000))
        );
        List<Message> rawHistory = List.of(
                new UserMessage("goal"),
                older,
                newest
        );

        List<Message> visible = manager.prepareNextContext(
                rawHistory,
                1,
                2,
                "goal"
        );

        assertThat(responseData(visible, 1)).isEqualTo(OMITTED_TOOL_RESULT);
        ToolResponseMessage visibleNewest = (ToolResponseMessage) visible.get(2);
        assertThat(visibleNewest.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly(
                        "a".repeat(9_000),
                        "b".repeat(9_000),
                        "c".repeat(9_000)
                );
        assertThat(newest.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly(
                        "a".repeat(9_000),
                        "b".repeat(9_000),
                        "c".repeat(9_000)
                );
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private ToolResponseMessage.ToolResponse response(
            String id,
            String responseData) {

        return new ToolResponseMessage.ToolResponse(
                id,
                "read_source",
                responseData
        );
    }

    private ToolResponseMessage toolMessage(
            ToolResponseMessage.ToolResponse... responses) {

        return ToolResponseMessage.builder()
                .responses(List.of(responses))
                .build();
    }

    private String responseData(
            List<Message> history,
            int index) {

        ToolResponseMessage message = (ToolResponseMessage) history.get(index);
        return message.getResponses().get(0).responseData();
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(text))
        ));
    }
}
