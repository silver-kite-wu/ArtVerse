package com.artverse.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentUserInputRequestTest {
    private AgentUserInputRequest question(boolean freeText) {
        return new AgentUserInputRequest("Choose", List.of(
                new AgentUserInputRequest.Option("a", "Same label", "First choice", true),
                new AgentUserInputRequest.Option("b", "Same label", "Second choice", false)),
                freeText, "", "USER_QUESTION", "call-1", 1L);
    }

    @Test void optionOnlyAnswersUseTheOriginalIdEvenWhenLabelsAreEqual() {
        var request = question(false);
        assertThat(request.resolveAnswer(null, "b")).isEqualTo("Same label");
        assertThatThrownBy(() -> request.resolveAnswer("Same label", null)).hasMessageContaining("仅接受原选项");
        assertThatThrownBy(() -> request.resolveAnswer(null, "unknown")).hasMessageContaining("原问题提供的选项");
    }

    @Test void exactlyOneBranchIsRequiredAndFreeTextIsBounded() {
        var request = question(true);
        assertThat(request.resolveAnswer("  explanation  ", null)).isEqualTo("explanation");
        assertThatThrownBy(() -> request.resolveAnswer(null, null)).hasMessageContaining("一项选择");
        assertThatThrownBy(() -> request.resolveAnswer("", "a")).hasMessageContaining("一项选择");
        assertThatThrownBy(() -> request.resolveAnswer(" ", null)).hasMessageContaining("4000");
        assertThatThrownBy(() -> request.resolveAnswer("x".repeat(4001), null)).hasMessageContaining("4000");
    }
}
