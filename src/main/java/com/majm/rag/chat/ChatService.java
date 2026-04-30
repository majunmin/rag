package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.CreateConversationRequest;
import com.majm.rag.retrieval.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

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

    public String buildContext(UUID knowledgeBaseId, String query, int topK) {
        List<Document> chunks = retrievalService.search(knowledgeBaseId, query, topK);
        return chunks.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    public Flux<String> chat(ChatRequest request) {
        String context = buildContext(request.knowledgeBaseId(), request.question(), request.topK());
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
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));

        String context = buildContext(conv.getKnowledgeBaseId(), request.question(), request.topK());

        List<Map<String, String>> history = conv.getMessages();
        history.add(Map.of("role", "user", "content", request.question()));
        conv.setMessages(history);
        conversationRepository.save(conv);

        StringBuilder assistantReply = new StringBuilder();

        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .messages(history.stream()
                .map(m -> {
                    String role = m.get("role");
                    return (role != null && role.equals("user"))
                        ? (Message) new UserMessage(m.get("content"))
                        : (Message) new AssistantMessage(m.get("content"));
                })
                .collect(Collectors.toList()))
            .stream()
            .content()
            .doOnNext(assistantReply::append)
            .doOnComplete(() -> persistenceService.appendAssistantMessage(conversationId, assistantReply.toString()));
    }
}
