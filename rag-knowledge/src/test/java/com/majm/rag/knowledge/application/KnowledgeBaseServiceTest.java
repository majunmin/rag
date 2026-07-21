package com.majm.rag.knowledge.application;

import com.majm.rag.common.exception.ResourceNotFoundException;
import com.majm.rag.knowledge.application.port.StorageService;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;
import com.majm.rag.knowledge.api.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.api.dto.UpdateKnowledgeBaseRequest;
import com.majm.rag.knowledge.infrastructure.persistence.DocumentRepository;
import com.majm.rag.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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
    private ChunkQueryService chunkQueryService;

    @Mock
    private StorageService storageService;

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
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    void delete_removesChunksKbAndEveryUploadedFile() {
        UUID kbId = savedKb.getId();
        when(repository.existsById(kbId)).thenReturn(true);
        when(documentRepository.findFilePathsByKnowledgeBaseId(kbId))
            .thenReturn(List.of("/data/uploads/" + kbId + "/a.pdf",
                                "/data/uploads/" + kbId + "/b.txt"));

        service.delete(kbId);

        // Chunks first (vector store), then KB row (FK cascade clears documents),
        // then disk files — order doesn't change correctness, but at minimum every
        // uploaded file must be deleted so we don't leak orphans.
        verify(chunkQueryService).deleteByKnowledgeBase(kbId);
        verify(repository).deleteById(kbId);
        verify(storageService).delete("/data/uploads/" + kbId + "/a.pdf");
        verify(storageService).delete("/data/uploads/" + kbId + "/b.txt");
        verifyNoMoreInteractions(storageService);
    }

    @Test
    void delete_throwsAndTouchesNothing_whenKbMissing() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(chunkQueryService, storageService);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void update_shouldModifyFieldsAndSave() {
        when(repository.findById(savedKb.getId())).thenReturn(Optional.of(savedKb));
        var request = new UpdateKnowledgeBaseRequest("Updated Name", null, 256, 32);

        KnowledgeBase result = service.update(savedKb.getId(), request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getChunkSize()).isEqualTo(256);
    }
}
