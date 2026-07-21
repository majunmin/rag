package com.majm.rag.retrieval.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HydeRewriterTest {

    @Test
    void generatesHypotheticalAndPreservesOriginalForRerank() {
        var hyde = new HydeRewriter(RewriteTestSupport.mockChatClient(
            "Spring AI is a framework for integrating large language models into Spring applications. "
                + "It provides a unified API across providers including OpenAI, Anthropic, and Ollama."));

        RewriteResult out = hyde.rewrite("What is Spring AI?", List.of());

        assertThat(out.originalQuery()).isEqualTo("What is Spring AI?");
        assertThat(out.embeddingQuery()).contains("framework").contains("Spring AI");
        assertThat(out.embeddingQuery()).isNotEqualTo(out.originalQuery());
        assertThat(out.expandedQueries()).isEmpty();
    }

    @Test
    void runawayOutput_fallsBackToPassthrough() {
        // 1500 chars - exceeds MAX_HYPOTHETICAL_LENGTH(1200)
        var hyde = new HydeRewriter(RewriteTestSupport.mockChatClient("noise ".repeat(300)));

        RewriteResult out = hyde.rewrite("query", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("query");
    }

    @Test
    void blankOutput_fallsBackToPassthrough() {
        var hyde = new HydeRewriter(RewriteTestSupport.mockChatClient(""));

        RewriteResult out = hyde.rewrite("query", List.of());

        assertThat(out.embeddingQuery()).isEqualTo("query");
    }

    @Test
    void llmException_fallsBackToPassthrough() {
        var hyde = new HydeRewriter(
            RewriteTestSupport.mockChatClientThrowing(new RuntimeException("rate limited")));

        RewriteResult out = hyde.rewrite("question", List.of());

        assertThat(out.originalQuery()).isEqualTo("question");
        assertThat(out.embeddingQuery()).isEqualTo("question");
    }

    @Test
    void historyIsIgnored() {
        // HyDE doesn't need history; pass some, expect identical behavior.
        var hyde = new HydeRewriter(
            RewriteTestSupport.mockChatClient("A hypothetical passage about the topic."));

        RewriteResult withHistory = hyde.rewrite("q",
            List.of(java.util.Map.of("role", "user", "content", "earlier")));
        RewriteResult noHistory = hyde.rewrite("q", List.of());

        assertThat(withHistory.embeddingQuery()).isEqualTo(noHistory.embeddingQuery());
    }

    @Test
    void name_isHyde() {
        assertThat(new HydeRewriter(RewriteTestSupport.mockChatClient("")).name()).isEqualTo("hyde");
    }
}
