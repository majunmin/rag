package com.majm.rag.retrieval.rewrite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Hypothetical Document Embeddings (Gao et al., 2022): ask the LLM to write
 * a brief passage that <i>would</i> answer the user's question, then embed
 * that passage instead of the raw question for vector recall.
 *
 * <p>Why it helps: the embedding space of factual passages and the embedding
 * space of bare questions are not the same neighborhood. Embedding a generated
 * "answer-shaped" text lands closer to real documents.
 *
 * <p>We preserve the original {@code query} for the rerank stage — rerank
 * scores (real intent, candidate), not (HyDE doc, candidate).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HydeRewriter implements QueryRewriter {

    static final String NAME = "hyde";

    /** Soft length cap to detect runaway generations. */
    private static final int MAX_HYPOTHETICAL_LENGTH = 1200;

    private static final String SYSTEM_PROMPT = """
        You generate a brief factual passage that would plausibly answer a
        user's question, for the purpose of semantic search retrieval.

        Rules:
        - Write 2-4 sentences in the same language as the question.
        - Write in declarative style, as if quoted from a reference document.
        - Be specific and use vocabulary the user likely did not use.
        - Do NOT add disclaimers, do NOT say "I don't know", do NOT ask
          clarifying questions. If you are unsure of facts, write a plausible
          passage anyway — its only purpose is to seed embedding similarity.
        - Output ONLY the passage. No preface, no markdown, no quotes.
        """;

    private final ChatClient chatClient;

    @Override
    public RewriteResult rewrite(String query, List<Map<String, String>> history) {
        try {
            String hypothetical = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Question: " + query)
                .call()
                .content();

            String cleaned = StringUtils.trimToNull(hypothetical);
            if (cleaned == null || cleaned.length() > MAX_HYPOTHETICAL_LENGTH) {
                log.warn("hyde produced suspicious output (len={}); using original query as embedding text",
                    cleaned == null ? 0 : cleaned.length());
                return RewriteResult.passthrough(query);
            }
            log.debug("hyde: generated {} chars of hypothetical context for '{}'",
                cleaned.length(), query);
            // Original intent preserved for rerank; HyDE text used for embedding only.
            return new RewriteResult(query, cleaned, List.of());
        } catch (Exception e) {
            log.warn("hyde failed ({}); using original query for embedding", e.getMessage());
            return RewriteResult.passthrough(query);
        }
    }

    @Override
    public String name() {
        return NAME;
    }
}
