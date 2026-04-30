package com.majm.rag.knowledge;

import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.DocumentChunkRepository;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;
import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository repository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @InjectMocks
    private KnowledgeBaseService service;

    private KnowledgeBase savedKb;

    @BeforeEach
    void setUp() {
        savedKb = new KnowledgeBase();
        savedKb.setId(UUID.randomUUID());
        savedKb.setName("Test KB");
        savedKb.setEmbeddingModel("text-embedding-3-small");
        savedKb.setChunkSize(512);
        savedKb.setChunkOverlap(64);
        savedKb.setStatus(KnowledgeBaseStatus.ACTIVE);
    }

    @Test
    void create_shouldPersistAndReturnKnowledgeBase() {
        when(repository.save(any())).thenReturn(savedKb);
        var request = new CreateKnowledgeBaseRequest("Test KB", "desc", "text-embedding-3-small", 512, 64);

        KnowledgeBase result = service.create(request);

        assertThat(result.getName()).isEqualTo("Test KB");
        verify(repository).save(any(KnowledgeBase.class));
    }

    @Test
    void getById_shouldThrowWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    void update_shouldModifyFieldsAndSave() {
        when(repository.findById(savedKb.getId())).thenReturn(Optional.of(savedKb));
        when(repository.save(any())).thenReturn(savedKb);
        var request = new UpdateKnowledgeBaseRequest("Updated Name", null, 256, 32);

        KnowledgeBase result = service.update(savedKb.getId(), request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getChunkSize()).isEqualTo(256);
    }
}
