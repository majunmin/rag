package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.ChunkQueryService;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionConsumerTest {

    @Mock private KnowledgeBaseService kbService;
    @Mock private IngestionStatusService statusService;
    @Mock private DocumentParserFactory parserFactory;
    @Mock private VectorStore vectorStore;
    @Mock private ChunkQueryService chunkQueryService;

    @InjectMocks private IngestionConsumer consumer;

    @Test
    void consume_skipsDuplicateMessageWhenDocumentIsAlreadyDone() {
        UUID documentId = UUID.randomUUID();
        IngestionMessage message = new IngestionMessage(documentId, UUID.randomUUID());
        when(statusService.markProcessing(documentId)).thenReturn(Optional.empty());
        consumer.consume(message);

        verifyNoInteractions(kbService, parserFactory, vectorStore, chunkQueryService);
        verify(statusService, never()).markDone(documentId, 0);
    }

    @Test
    void consume_replacesVectorsUsingDeterministicChunkIds() {
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        IngestionMessage message = new IngestionMessage(documentId, knowledgeBaseId);

        com.majm.rag.knowledge.domain.Document stored =
            new com.majm.rag.knowledge.domain.Document();
        stored.setId(documentId);
        stored.setName("guide.txt");
        stored.setFileType("TXT");
        stored.setFilePath("/tmp/guide.txt");
        stored.setStatus(DocumentStatus.PENDING);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(knowledgeBaseId);
        kb.setChunkSize(128);
        kb.setChunkOverlap(0);

        DocumentReader reader = () -> List.of(
            new org.springframework.ai.document.Document("alpha beta gamma", Map.of("source", "upload")));

        when(statusService.markProcessing(documentId)).thenReturn(Optional.of(stored));
        when(statusService.markDone(documentId, 1)).thenReturn(true);
        when(kbService.getById(knowledgeBaseId)).thenReturn(kb);
        when(parserFactory.create("TXT", "/tmp/guide.txt")).thenReturn(reader);

        consumer.consume(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> chunks =
            ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(chunks.capture());

        assertThat(chunks.getValue()).singleElement().satisfies(chunk -> {
            String expectedId = UUID.nameUUIDFromBytes(
                (documentId + ":0").getBytes(StandardCharsets.UTF_8)).toString();
            assertThat(chunk.getId()).isEqualTo(expectedId);
            assertThat(chunk.getMetadata())
                .containsEntry("source", "upload")
                .containsEntry("document_id", documentId.toString())
                .containsEntry("chunk_index", 0);
        });

        InOrder order = inOrder(vectorStore, chunkQueryService);
        order.verify(vectorStore).add(anyList());
        order.verify(chunkQueryService).deleteByDocumentFromIndex(documentId, 1);
        verify(statusService).markDone(documentId, 1);
    }

    @Test
    void consume_removesNewVectorsWhenDocumentWasDeletedBeforeFinalization() {
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        IngestionMessage message = new IngestionMessage(documentId, knowledgeBaseId);
        com.majm.rag.knowledge.domain.Document stored =
            new com.majm.rag.knowledge.domain.Document();
        stored.setId(documentId);
        stored.setName("guide.txt");
        stored.setFileType("TXT");
        stored.setFilePath("/tmp/guide.txt");

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(knowledgeBaseId);
        kb.setChunkSize(128);
        kb.setChunkOverlap(0);

        when(statusService.markProcessing(documentId)).thenReturn(Optional.of(stored));
        when(statusService.markDone(documentId, 1)).thenReturn(false);
        when(kbService.getById(knowledgeBaseId)).thenReturn(kb);
        when(parserFactory.create("TXT", "/tmp/guide.txt")).thenReturn(() -> List.of(
            new org.springframework.ai.document.Document("content")));

        consumer.consume(message);

        InOrder order = inOrder(vectorStore, chunkQueryService);
        order.verify(vectorStore).add(anyList());
        order.verify(chunkQueryService).deleteByDocumentFromIndex(documentId, 1);
        order.verify(chunkQueryService).deleteByDocument(documentId);
        verify(statusService, never()).markFailed(any(), any());
    }

    @Test
    void consume_preservesPreviousVectorsWhenReplacementAddFails() {
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        IngestionMessage message = new IngestionMessage(documentId, knowledgeBaseId);
        com.majm.rag.knowledge.domain.Document stored =
            new com.majm.rag.knowledge.domain.Document();
        stored.setId(documentId);
        stored.setName("guide.txt");
        stored.setFileType("TXT");
        stored.setFilePath("/tmp/guide.txt");

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(knowledgeBaseId);
        kb.setChunkSize(128);
        kb.setChunkOverlap(0);

        when(statusService.markProcessing(documentId)).thenReturn(Optional.of(stored));
        when(statusService.markFailed(any(), anyString())).thenReturn(true);
        when(kbService.getById(knowledgeBaseId)).thenReturn(kb);
        when(parserFactory.create("TXT", "/tmp/guide.txt")).thenReturn(() -> List.of(
            new org.springframework.ai.document.Document("content")));
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding failed"))
            .when(vectorStore).add(anyList());

        assertThatThrownBy(() -> consumer.consume(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("embedding failed");

        verify(chunkQueryService, never()).deleteByDocument(documentId);
        verify(chunkQueryService, never()).deleteByDocumentFromIndex(any(), anyInt());
    }
}
