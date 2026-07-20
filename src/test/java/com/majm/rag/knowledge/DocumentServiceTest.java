package com.majm.rag.knowledge;

import com.majm.rag.ingestion.StorageService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
}
