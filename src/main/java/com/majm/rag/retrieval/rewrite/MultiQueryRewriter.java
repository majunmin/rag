package com.majm.rag.retrieval.rewrite;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Asks the LLM for N alternate phrasings of the question. The original query
 * stays in {@code embeddingQuery}; the LLM's paraphrases become
 * {@code expandedQueries}, so {@code RetrievalService} fans out N+1 recall
 * calls and fuses them with Reciprocal Rank Fusion.
 *
 * <p>Trade-off vs HyDE: this costs N extra vector searches per chat, but each
 * paraphrase covers a different vocabulary axis. Useful when the corpus uses
 * domain jargon the user doesn't.
 */
@Slf4j
@Component
public class MultiQueryRewriter implements QueryRewriter {

    static final String NAME = "multi-query";

    /** Soft cap; LLM output longer than this is a red flag (instruction-following failure). */
    private static final int MAX_PARAPHRASE_LENGTH = 300;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You generate alternate phrasings of a user's question for information retrieval.

        Rules:
        - Output exactly {n} phrasings, one per line.
        - No numbering, no bullets, no quotes, no preface.
        - Each phrasing should use different vocabulary or angle than the original.
        - Stay in the same language as the original question.
        - Each line should be a single concise question.
        """;

    private final ChatClient chatClient;
    private final int paraphraseCount;

    public MultiQueryRewriter(
        ChatClient chatClient,
        @Value("${app.query-rewrite.multi-query-count:3}") int paraphraseCount
    ) {
        this.chatClient = chatClient;
        // 1 paraphrase isn't worth a round-trip; cap at 8 to bound vector store cost.
        this.paraphraseCount = Math.max(2, Math.min(paraphraseCount, 8));
    }

    @Override
    public RewriteResult rewrite(String query, List<Map<String, String>> history) {
        try {
            String systemPrompt = SYSTEM_PROMPT_TEMPLATE.replace("{n}", String.valueOf(paraphraseCount));
            String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(query)
                .call()
                .content();

            List<String> paraphrases = parse(raw, query);
            if (CollectionUtils.isEmpty(paraphrases)) {
                log.warn("multi-query produced no parseable paraphrases; falling back to single-query");
                return RewriteResult.passthrough(query);
            }
            log.debug("multi-query generated {} paraphrases for '{}'", paraphrases.size(), query);
            return new RewriteResult(query, query, paraphrases);
        } catch (Exception e) {
            log.warn("multi-query failed ({}); using single-query recall", e.getMessage());
            return RewriteResult.passthrough(query);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Split LLM output into clean paraphrases. Drops blanks, drops anything that
     * equals (or is contained in) the original, deduplicates while preserving
     * order, and rejects suspiciously long lines.
     */
    private List<String> parse(String raw, String original) {
        if (StringUtils.isBlank(raw)) return List.of();
        String normalizedOriginal = StringUtils.lowerCase(StringUtils.trim(original));

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : Arrays.asList(raw.split("\\R"))) {
            String cleaned = stripBulletAndQuotes(line);
            if (StringUtils.isBlank(cleaned)) continue;
            if (cleaned.length() > MAX_PARAPHRASE_LENGTH) continue;
            String normalized = StringUtils.lowerCase(cleaned);
            if (normalized.equals(normalizedOriginal)) continue;
            out.add(cleaned);
        }
        return new ArrayList<>(out);
    }

    private static String stripBulletAndQuotes(String line) {
        String s = StringUtils.trim(line);
        // Drop common list prefixes: "1.", "1)", "-", "*", "•"
        s = s.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "");
        // Drop wrapping quotes if balanced.
        if (s.length() >= 2) {
            char first = s.charAt(0), last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                || (first == '“' && last == '”') || (first == '「' && last == '」')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
