package com.majm.rag.chat;

import com.majm.rag.retrieval.RetrievalService;
import com.majm.rag.retrieval.rewrite.QueryRewriteService;
import com.majm.rag.retrieval.rewrite.RewriteResult;
import com.majm.rag.chat.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private RetrievalService retrievalService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.StreamResponseSpec streamSpec;
    @Mock private ConversationPersistenceService persistenceService;
    @Mock private QueryRewriteService queryRewriteService;

    @InjectMocks
    private ChatService chatService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpChatStream() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("answer"));
    }

    @Test
    void chat_shouldExposeTheExactRetrievedChunksAndTokenStream() {
        UUID kbId = UUID.randomUUID();
        Document doc = new Document("chunk-id", "chunk text",
            Map.of("document_name", "guide.pdf", "chunk_index", 2));

        when(queryRewriteService.rewrite(any(), any()))
            .thenAnswer(inv -> RewriteResult.passthrough(inv.getArgument(0)));
        when(retrievalService.search(any(), any(), anyInt(), any(RewriteResult.class)))
            .thenReturn(List.of(doc));

        ChatStream stream = chatService.chat(new ChatRequest(kbId, "test query", 5));

        assertThat(stream.context()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo("chunk text");
            assertThat(item.metadata())
                .containsEntry("document_name", "guide.pdf")
                .containsEntry("chunk_index", 2);
        });
        StepVerifier.create(stream.tokens())
            .expectNext("answer")
            .verifyComplete();
    }
}
