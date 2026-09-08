package com.guodi.aikb.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class InMemoryTurnContextManagerTest {

    @Test
    void keepsSessionsIndependentAndReturnsRecordedTurns() {
        InMemoryTurnContextManager manager = new InMemoryTurnContextManager();

        manager.recordTurn(1L, "first question", "first answer");
        manager.recordTurn(2L, "other question", "other answer");

        assertThat(manager.prepareContext(1L))
                .hasSize(2)
                .satisfiesExactly(
                        message -> assertThat(message)
                                .isInstanceOfSatisfying(UserMessage.class,
                                        item -> assertThat(item.getText()).isEqualTo("first question")),
                        message -> assertThat(message)
                                .isInstanceOfSatisfying(AssistantMessage.class,
                                        item -> assertThat(item.getText()).isEqualTo("first answer"))
                );
        assertThat(manager.prepareContext(2L)).hasSize(2);
    }

    @Test
    void retainsOnlyTheLatestTenTurns() {
        InMemoryTurnContextManager manager = new InMemoryTurnContextManager();
        for (int index = 1; index <= 12; index++) {
            manager.recordTurn(1L, "question-" + index, "answer-" + index);
        }

        assertThat(manager.prepareContext(1L)).hasSize(20);
        assertThat(manager.prepareContext(1L).getFirst().getText()).isEqualTo("question-3");
    }
}
