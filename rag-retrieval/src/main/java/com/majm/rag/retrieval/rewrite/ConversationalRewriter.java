package com.majm.rag.retrieval.rewrite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves coreference in follow-up questions.
 *
 * <p>Single-turn (empty history): passthrough — no LLM call.
 *
 * <p>Multi-turn: sends the last few turns + the user's follow-up to the LLM
 * and asks for a standalone rephrasing. Output replaces both the embedding
 * query and the original query downstream — for conversational coreference
 * the rewrite IS the user's intent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationalRewriter implements QueryRewriter {

    static final String NAME = "conversational";

    /** Only feed the last N turns to keep prompt tokens bounded. */
    private static final int HISTORY_WINDOW = 6;

    /** Maximum input length we trust to be a real coreference rewrite. */
    private static final int MAX_REWRITE_LENGTH = 500;

    private static final String SYSTEM_PROMPT = """
        You rewrite follow-up questions into standalone questions.

        Rules:
        - Read the conversation history.
        - If the follow-up question contains pronouns ("it", "that", "他", "它", "那个")
          or implicit references, rewrite it as a standalone question that includes
          the referenced entity from the history.
        - If the follow-up is already standalone, output it UNCHANGED.
        - Output ONLY the rewritten question. No preface, no explanation, no quotes.
        """;

    private final ChatClient chatClient;

    @Override
    public RewriteResult rewrite(String query, List<Map<String, String>> history) {
        if (CollectionUtils.isEmpty(history)) {
            // No history → nothing to resolve. Passthrough is correct and cheap.
            return RewriteResult.passthrough(query);
        }
        try {
            String userPrompt = buildPrompt(query, history);
            String rewritten = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

            String cleaned = StringUtils.trimToNull(rewritten);
            if (cleaned == null || cleaned.length() > MAX_REWRITE_LENGTH) {
                log.warn("conversational rewrite returned suspicious output (len={}); using original",
                    cleaned == null ? 0 : cleaned.length());
                return RewriteResult.passthrough(query);
            }
            log.debug("conversational rewrite: '{}' -> '{}'", query, cleaned);
            return new RewriteResult(cleaned, cleaned, List.of());
        } catch (Exception e) {
            log.warn("conversational rewrite failed ({}); using original query", e.getMessage());
            return RewriteResult.passthrough(query);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    private static String buildPrompt(String query, List<Map<String, String>> history) {
        int start = Math.max(0, history.size() - HISTORY_WINDOW);
        StringBuilder sb = new StringBuilder("Conversation history:\n");
        for (int i = start; i < history.size(); i++) {
            Map<String, String> m = history.get(i);
            String role = StringUtils.defaultString(m.get("role"), "user");
            String content = StringUtils.defaultString(m.get("content"));
            sb.append(role).append(": ").append(content).append('\n');
        }
        sb.append("\nFollow-up question: ").append(query);
        sb.append("\n\nStandalone question:");
        return sb.toString();
    }
}
