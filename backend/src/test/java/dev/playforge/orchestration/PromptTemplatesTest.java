package dev.playforge.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplatesTest {

    @Test
    void reviewTruncatesHugeDiff() {
        String review = PromptTemplates.review("p", "+x\n".repeat(100_000));
        assertThat(review.length()).isLessThan(70_000);
        assertThat(review).contains("[diff truncated]");
    }

    @Test
    void codePromptMentionsTemplateAndProbeSdk() {
        String p = PromptTemplates.code("x");
        assertThat(p).contains("src/probe-sdk.js").contains("installProbe").contains("base: './'");
    }

    @Test
    void reviewKeepsSmallDiffIntact() {
        String review = PromptTemplates.review("p", "+console.log('hi')");
        assertThat(review).contains("+console.log('hi')").doesNotContain("[diff truncated]");
    }
}
