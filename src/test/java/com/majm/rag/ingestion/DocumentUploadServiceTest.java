package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock private KnowledgeBaseService kbService;
    @Mock private DocumentRepository documentRepository;
    @Mock private StorageService storageService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private DocumentUploadService uploadService;

    @Test
    void upload_shouldCreateDocumentAndPublishToKafka() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);

        Document savedDoc = new Document();
        savedDoc.setId(UUID.randomUUID());
        savedDoc.setStatus(DocumentStatus.PENDING);
        savedDoc.setKnowledgeBase(kb);

        when(kbService.getById(kbId)).thenReturn(kb);
        when(storageService.store(any(), any(), any())).thenReturn("/data/uploads/test.pdf");
        when(documentRepository.save(any())).thenReturn(savedDoc);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
            "application/pdf", "pdf content".getBytes());

        UploadDocumentResponse response = uploadService.upload(kbId, file);

        assertThat(response.status()).isEqualTo("PENDING");
        verify(kafkaTemplate).send(any(), any(), any());
    }
}
