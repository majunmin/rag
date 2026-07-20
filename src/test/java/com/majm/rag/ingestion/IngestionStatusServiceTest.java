package com.majm.rag.ingestion;

import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionStatusServiceTest {

    @Mock private DocumentRepository documentRepository;

    @InjectMocks private IngestionStatusService statusService;

    @Test
    void markProcessing_clearsPreviousFailureMessage() {
        UUID documentId = UUID.randomUUID();
        Document failed = new Document();
        failed.setId(documentId);
        failed.setStatus(DocumentStatus.FAILED);
        failed.setErrorMessage("provider unavailable");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(failed));

        assertThat(statusService.markProcessing(documentId)).containsSame(failed);

        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(failed.getErrorMessage()).isNull();
    }

    @Test
    void markFailed_doesNotOverwriteCompletedDocument() {
        UUID documentId = UUID.randomUUID();
        Document done = new Document();
        done.setId(documentId);
        done.setStatus(DocumentStatus.DONE);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(done));

        assertThat(statusService.markFailed(documentId, "late failure")).isTrue();

        assertThat(done.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(done.getErrorMessage()).isNull();
    }

    @Test
    void markDone_reportsDeletedDocument() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThat(statusService.markDone(documentId, 3)).isFalse();
    }
}
