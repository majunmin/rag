package com.majm.rag.retrieval.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiQueryRewriterTest {

    @Test
    void parsesPlainNewlineSeparatedParaphrases() {
        var rewriter = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(
            "How does Spring AI work?\n"
                + "What are the features of Spring AI?\n"
                + "Spring AI overview"), 3);

        RewriteResult out = rewriter.rewrite("Tell me about Spring AI", List.of());

        assertThat(out.originalQuery()).isEqualTo("Tell me about Spring AI");
        assertThat(out.embeddingQuery()).isEqualTo("Tell me about Spring AI");
        assertThat(out.expandedQueries()).containsExactly(
            "How does Spring AI work?",
            "What are the features of Spring AI?",
            "Spring AI overview");
    }

    @Test
    void stripsNumberingBulletsAndQuotes() {
        var rewriter = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(
            "1. What is Spring AI?\n"
                + "- How to use Spring AI?\n"
                + "\"Spring AI tutorial\"\n"
                + "* Spring AI features"), 4);

        RewriteResult out = rewriter.rewrite("Spring AI guide", List.of());

        assertThat(out.expandedQueries()).containsExactly(
            "What is Spring AI?",
            "How to use Spring AI?",
            "Spring AI tutorial",
            "Spring AI features");
    }

    @Test
    void dropsLinesThatMatchOriginalCaseInsensitively() {
        var rewriter = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(
            "spring ai overview\n"
                + "What is Spring AI?\n"
                + "Spring AI Overview"), 3);

        RewriteResult out = rewriter.rewrite("Spring AI overview", List.of());

        // Both case-variants of the original drop out; only the genuine paraphrase remains.
        assertThat(out.expandedQueries()).containsExactly("What is Spring AI?");
    }

    @Test
    void deduplicatesIdenticalParaphrases() {
        var rewriter = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(
            "What is X?\n"
                + "What is X?\n"
                + "How does X work?"), 3);

        RewriteResult out = rewriter.rewrite("X", List.of());

        assertThat(out.expandedQueries()).containsExactly("What is X?", "How does X work?");
    }

    @Test
    void emptyResponse_fallsBackToPassthrough() {
        var rewriter = new MultiQueryRewriter(RewriteTestSupport.mockChatClient("\n\n\n"), 3);

        RewriteResult out = rewriter.rewrite("query", List.of());

        assertThat(out.expandedQueries()).isEmpty();
        assertThat(out.originalQuery()).isEqualTo("query");
    }

    @Test
    void llmException_fallsBackToPassthrough() {
        var rewriter = new MultiQueryRewriter(
            RewriteTestSupport.mockChatClientThrowing(new RuntimeException("oom")), 3);

        RewriteResult out = rewriter.rewrite("query", List.of());

        assertThat(out.expandedQueries()).isEmpty();
        assertThat(out.originalQuery()).isEqualTo("query");
    }

    @Test
    void paraphraseCountIsClampedToReasonableRange() {
        // 1 is clamped up to 2.
        var low = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(""), 1);
        // 50 is clamped down to 8.
        var high = new MultiQueryRewriter(RewriteTestSupport.mockChatClient(""), 50);

        // Smoke test: constructors don't throw and rewriters are usable.
        assertThat(low.name()).isEqualTo("multi-query");
        assertThat(high.name()).isEqualTo("multi-query");
    }
}
