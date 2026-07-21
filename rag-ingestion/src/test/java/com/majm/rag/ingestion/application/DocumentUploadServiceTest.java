package com.majm.rag.ingestion.application;

import com.majm.rag.ingestion.api.dto.UploadDocumentResponse;
import com.majm.rag.ingestion.infrastructure.persistence.IngestionOutboxRepository;
import com.majm.rag.knowledge.application.port.StorageService;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import com.majm.rag.knowledge.application.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock private KnowledgeBaseService kbService;
    @Mock private DocumentRepository documentRepository;
    @Mock private StorageService storageService;
    @Mock private IngestionOutboxRepository outboxRepository;

    @InjectMocks
    private DocumentUploadService uploadService;

    @Test
    void upload_shouldCreateDocumentAndEnqueueOutboxEvent() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);

        Document savedDoc = new Document();
        savedDoc.setId(UUID.randomUUID());
        savedDoc.setStatus(DocumentStatus.PENDING);
        savedDoc.setKnowledgeBase(kb);

        when(kbService.getById(kbId)).thenReturn(kb);
        when(storageService.store(any(), any(), any())).thenReturn("/data/uploads/test.pdf");
        when(documentRepository.saveAndFlush(any())).thenReturn(savedDoc);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
            "application/pdf", "pdf content".getBytes());

        UploadDocumentResponse response = uploadService.upload(kbId, file);

        assertThat(response.status()).isEqualTo("PENDING");
        InOrder order = inOrder(documentRepository, outboxRepository);
        order.verify(documentRepository).saveAndFlush(any());
        order.verify(outboxRepository).enqueue(savedDoc.getId(), kbId);
    }

    @Test
    void upload_shouldRejectEmptyFile() {
        UUID kbId = UUID.randomUUID();
        MockMultipartFile empty = new MockMultipartFile("file", "test.pdf",
            "application/pdf", new byte[0]);

        assertThatThrownBy(() -> uploadService.upload(kbId, empty))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitUrl_shouldCreateUrlDocumentAndEnqueueOutboxEvent() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        when(kbService.getById(kbId)).thenReturn(kb);
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UploadDocumentResponse response = uploadService.submitUrl(
            kbId, URI.create("https://example.com/guide"));

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).saveAndFlush(document.capture());
        assertThat(document.getValue().getName()).isEqualTo("https://example.com/guide");
        assertThat(document.getValue().getFileType()).isEqualTo("URL");
        assertThat(document.getValue().getFilePath()).isEqualTo("https://example.com/guide");
        verify(storageService, never()).store(any(), any(), any());
        verify(outboxRepository).enqueue(document.getValue().getId(), kbId);
        assertThat(response.status()).isEqualTo("PENDING");
    }

    @Test
    void upload_shouldRejectUnsupportedExtension() {
        UUID kbId = UUID.randomUUID();
        MockMultipartFile bad = new MockMultipartFile("file", "evil.exe",
            "application/octet-stream", "data".getBytes());

        assertThatThrownBy(() -> uploadService.upload(kbId, bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported");
    }
}
