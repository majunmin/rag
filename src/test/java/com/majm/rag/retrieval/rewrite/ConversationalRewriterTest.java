package com.majm.rag.retrieval.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationalRewriterTest {

    @Test
    void emptyHistory_returnsPassthroughWithoutCallingLLM() {
        var rewriter = new ConversationalRewriter(
            RewriteTestSupport.mockChatClient("SHOULD NEVER BE READ"));

        RewriteResult out = rewriter.rewrite("What is it?", List.of());

        assertThat(out.originalQuery()).isEqualTo("What is it?");
        assertThat(out.embeddingQuery()).isEqualTo("What is it?");
        assertThat(out.expandedQueries()).isEmpty();
    }

    @Test
    void multiTurn_rewritesFollowUpUsingHistory() {
        var rewriter = new ConversationalRewriter(
            RewriteTestSupport.mockChatClient("What are the pricing limits of Spring AI?"));

        var history = List.of(
            Map.of("role", "user", "content", "Tell me about Spring AI."),
            Map.of("role", "assistant", "content", "Spring AI is a Spring project for AI integration."));

        RewriteResult out = rewriter.rewrite("What about its limits?", history);

        assertThat(out.originalQuery()).isEqualTo("What are the pricing limits of Spring AI?");
        assertThat(out.embeddingQuery()).isEqualTo("What are the pricing limits of Spring AI?");
        assertThat(out.expandedQueries()).isEmpty();
    }

    @Test
    void suspiciouslyLongOutput_fallsBackToOriginal() {
        // LLM goes off the rails and produces a 600-char essay; we treat that as
        // failure rather than blasting it at the embedding service.
        String runaway = "x".repeat(600);
        var rewriter = new ConversationalRewriter(RewriteTestSupport.mockChatClient(runaway));

        RewriteResult out = rewriter.rewrite("ok?",
            List.of(Map.of("role", "user", "content", "earlier")));

        assertThat(out.originalQuery()).isEqualTo("ok?");
        assertThat(out.embeddingQuery()).isEqualTo("ok?");
    }

    @Test
    void llmException_fallsBackToOriginal() {
        var rewriter = new ConversationalRewriter(
            RewriteTestSupport.mockChatClientThrowing(new RuntimeException("upstream timeout")));

        RewriteResult out = rewriter.rewrite("follow-up",
            List.of(Map.of("role", "user", "content", "prior")));

        assertThat(out.originalQuery()).isEqualTo("follow-up");
    }

    @Test
    void blankResponse_fallsBackToOriginal() {
        var rewriter = new ConversationalRewriter(RewriteTestSupport.mockChatClient("   "));

        RewriteResult out = rewriter.rewrite("question",
            List.of(Map.of("role", "user", "content", "earlier turn")));

        assertThat(out.originalQuery()).isEqualTo("question");
    }

    @Test
    void name_isConversational() {
        var rewriter = new ConversationalRewriter(RewriteTestSupport.mockChatClient(""));

        assertThat(rewriter.name()).isEqualTo("conversational");
    }
}
