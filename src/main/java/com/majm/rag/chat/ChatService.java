package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.common.exception.ResourceNotFoundException;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.CreateConversationRequest;
import com.majm.rag.retrieval.RetrievalService;
import com.majm.rag.retrieval.rewrite.QueryRewriteService;
import com.majm.rag.retrieval.rewrite.RewriteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    static final String MSG_ROLE = "role";
    static final String MSG_CONTENT = "content";
    static final String ROLE_USER = "user";
    static final String ROLE_ASSISTANT = "assistant";

    private static final String RAG_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer questions based only on the provided context.
        If the context does not contain enough information, say so clearly.

        Context:
        {context}
        """;

    private final RetrievalService retrievalService;
    private final ConversationRepository conversationRepository;
    private final ChatClient chatClient;
    private final ConversationPersistenceService persistenceService;
    private final QueryRewriteService queryRewriteService;

    @Value("${app.chat.max-history-messages:20}")
    private int maxHistoryMessages;

    /**
     * Returns a list with at most {@code maxSize} most recent messages. If the
     * input is already within the cap, the same reference is returned.
     */
    static List<Map<String, String>> trimHistory(List<Map<String, String>> messages, int maxSize) {
        if (maxSize <= 0 || messages.size() <= maxSize) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - maxSize, messages.size()));
    }

    String buildContext(UUID knowledgeBaseId, String query, int topK,
                        List<Map<String, String>> history) {
        RewriteResult rewrite = queryRewriteService.rewrite(query, history);
        List<Document> chunks = retrievalService.search(knowledgeBaseId, query, topK, rewrite);
        return chunks.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    public Flux<String> chat(ChatRequest request) {
        String context = buildContext(
            request.knowledgeBaseId(), request.question(), request.topK(), List.of());
        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .user(request.question())
            .stream()
            .content();
    }

    @Transactional
    public Conversation createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setKnowledgeBaseId(request.knowledgeBaseId());
        return conversationRepository.save(conv);
    }

    public Flux<String> continueConversation(UUID conversationId, ConversationMessageRequest request) {
        Conversation conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));

        // Rewrite using the PRE-question history (so conversational rewriter
        // can resolve coreference against prior turns without seeing the
        // current question echoed in history).
        List<Map<String, String>> priorHistory = trimHistory(conv.getMessages(), maxHistoryMessages);
        String context = buildContext(
            conv.getKnowledgeBaseId(), request.question(), request.topK(), priorHistory);

        List<Map<String, String>> history = new ArrayList<>(priorHistory);
        history.add(Map.of(MSG_ROLE, ROLE_USER, MSG_CONTENT, request.question()));
        history = trimHistory(history, maxHistoryMessages);
        conv.setMessages(history);
        conversationRepository.save(conv);

        StringBuilder assistantReply = new StringBuilder();

        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .messages(history.stream()
                .map(m -> {
                    String role = m.get(MSG_ROLE);
                    return ROLE_USER.equals(role)
                        ? (Message) new UserMessage(m.get(MSG_CONTENT))
                        : (Message) new AssistantMessage(m.get(MSG_CONTENT));
                })
                .toList())
            .stream()
            .content()
            .doOnNext(assistantReply::append)
            .doOnComplete(() -> persistenceService.appendAssistantMessage(conversationId, assistantReply.toString()));
    }
}
