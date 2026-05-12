package com.majm.rag.chat;

import com.majm.rag.retrieval.RetrievalService;
import com.majm.rag.retrieval.rewrite.QueryRewriteService;
import com.majm.rag.retrieval.rewrite.RewriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private RetrievalService retrievalService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatClient chatClient;
    @Mock private ConversationPersistenceService persistenceService;
    @Mock private QueryRewriteService queryRewriteService;

    @InjectMocks
    private ChatService chatService;

    @Test
    void buildContext_shouldConcatenateChunkContents() {
        UUID kbId = UUID.randomUUID();
        Document doc1 = new Document("chunk one", Map.of());
        Document doc2 = new Document("chunk two", Map.of());

        when(queryRewriteService.rewrite(any(), any()))
            .thenAnswer(inv -> RewriteResult.passthrough(inv.getArgument(0)));
        when(retrievalService.search(any(), any(), anyInt(), any(RewriteResult.class)))
            .thenReturn(List.of(doc1, doc2));

        String context = chatService.buildContext(kbId, "test query", 5, List.of());

        assertThat(context).contains("chunk one");
        assertThat(context).contains("chunk two");
    }
}
