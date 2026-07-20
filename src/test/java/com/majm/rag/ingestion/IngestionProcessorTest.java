package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.ChunkQueryService;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.domain.Document;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionProcessorTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentParserFactory parserFactory;
    @Mock private VectorStore vectorStore;
    @Mock private ChunkQueryService chunkQueryService;

    @InjectMocks private IngestionProcessor processor;

    @Test
    void process_reportsMissingDocumentWithoutWritingVectors() {
        IngestionMessage message = message();
        when(documentRepository.findByIdForUpdate(message.documentId())).thenReturn(Optional.empty());

        assertThat(processor.process(message)).isEqualTo(IngestionProcessor.Result.MISSING);

        verifyNoInteractions(parserFactory, vectorStore, chunkQueryService);
    }

    @Test
    void process_serializesDuplicatesAndSkipsCompletedDocument() {
        IngestionMessage message = message();
        Document done = document(message, DocumentStatus.DONE);
        when(documentRepository.findByIdForUpdate(message.documentId())).thenReturn(Optional.of(done));

        assertThat(processor.process(message)).isEqualTo(IngestionProcessor.Result.ALREADY_DONE);

        verifyNoInteractions(parserFactory, vectorStore, chunkQueryService);
    }

    @Test
    void process_upsertsAndPrunesVectorsBeforeCompletingInOneTransaction() {
        IngestionMessage message = message();
        Document stored = document(message, DocumentStatus.PROCESSING);
        DocumentReader reader = () -> List.of(
            new org.springframework.ai.document.Document("alpha beta gamma", Map.of("source", "upload")));
        when(documentRepository.findByIdForUpdate(message.documentId())).thenReturn(Optional.of(stored));
        when(parserFactory.create("TXT", "/tmp/guide.txt")).thenReturn(reader);

        assertThat(processor.process(message)).isEqualTo(IngestionProcessor.Result.COMPLETED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> chunks =
            ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(chunks.capture());
        assertThat(chunks.getValue()).singleElement().satisfies(chunk -> {
            String expectedId = UUID.nameUUIDFromBytes(
                (message.documentId() + ":0").getBytes(StandardCharsets.UTF_8)).toString();
            assertThat(chunk.getId()).isEqualTo(expectedId);
            assertThat(chunk.getMetadata())
                .containsEntry("source", "upload")
                .containsEntry("document_id", message.documentId().toString())
                .containsEntry("chunk_index", 0);
        });

        InOrder order = inOrder(vectorStore, chunkQueryService);
        order.verify(vectorStore).add(anyList());
        order.verify(chunkQueryService).deleteByDocumentFromIndex(message.documentId(), 1);
        assertThat(stored.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(stored.getChunkCount()).isEqualTo(1);
        assertThat(stored.getErrorMessage()).isNull();
    }

    @Test
    void process_doesNotPruneOrCompleteWhenVectorWriteFails() {
        IngestionMessage message = message();
        Document stored = document(message, DocumentStatus.PROCESSING);
        when(documentRepository.findByIdForUpdate(message.documentId())).thenReturn(Optional.of(stored));
        when(parserFactory.create("TXT", "/tmp/guide.txt")).thenReturn(() -> List.of(
            new org.springframework.ai.document.Document("content")));
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding failed"))
            .when(vectorStore).add(anyList());

        assertThatThrownBy(() -> processor.process(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("embedding failed");

        verify(chunkQueryService, never()).deleteByDocumentFromIndex(message.documentId(), 1);
        assertThat(stored.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    }

    private IngestionMessage message() {
        return new IngestionMessage(UUID.randomUUID(), UUID.randomUUID());
    }

    private Document document(IngestionMessage message, DocumentStatus status) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(message.knowledgeBaseId());
        kb.setChunkSize(128);
        kb.setChunkOverlap(0);

        Document document = new Document();
        document.setId(message.documentId());
        document.setKnowledgeBase(kb);
        document.setName("guide.txt");
        document.setFileType("TXT");
        document.setFilePath("/tmp/guide.txt");
        document.setStatus(status);
        return document;
    }
}
