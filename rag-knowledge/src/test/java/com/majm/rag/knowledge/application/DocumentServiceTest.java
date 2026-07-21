package com.majm.rag.knowledge.application;

import com.majm.rag.knowledge.application.port.StorageService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChunkQueryService chunkQueryService;
    @Mock private StorageService storageService;

    @InjectMocks private DocumentService documentService;

    @Test
    void list_includesPersistedIngestionError() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PageRequest page = PageRequest.of(0, 20);
        Document failed = new Document();
        failed.setId(UUID.randomUUID());
        failed.setName("broken.pdf");
        failed.setFileType("PDF");
        failed.setStatus(DocumentStatus.FAILED);
        failed.setErrorMessage("embedding provider unavailable");

        when(documentRepository.findByKnowledgeBaseId(knowledgeBaseId, page))
            .thenReturn(new PageImpl<>(List.of(failed)));

        DocumentService.DocumentListItem item =
            documentService.list(knowledgeBaseId, page).getContent().getFirst();

        assertThat(item.errorMessage()).isEqualTo("embedding provider unavailable");
    }

    @Test
    void delete_urlDocumentDoesNotDeleteRemoteSourceAsLocalFile() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(knowledgeBaseId);
        Document document = new Document();
        document.setId(documentId);
        document.setKnowledgeBase(knowledgeBase);
        document.setFileType("URL");
        document.setFilePath("https://example.com/guide");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        documentService.delete(knowledgeBaseId, documentId);

        verify(chunkQueryService).deleteByDocument(documentId);
        verify(documentRepository).delete(document);
        verify(storageService, never()).delete("https://example.com/guide");
    }
}
