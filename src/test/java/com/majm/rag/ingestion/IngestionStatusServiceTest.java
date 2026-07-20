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
    void markProcessing_locksRowAndClearsPreviousFailure() {
        UUID documentId = UUID.randomUUID();
        Document failed = document(documentId, DocumentStatus.FAILED);
        failed.setErrorMessage("provider unavailable");
        when(documentRepository.findByIdForUpdate(documentId)).thenReturn(Optional.of(failed));

        assertThat(statusService.markProcessing(documentId))
            .isEqualTo(IngestionStatusService.ProcessingDecision.READY);
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(failed.getErrorMessage()).isNull();
    }

    @Test
    void markProcessing_reportsCompletedAndMissingDocuments() {
        UUID doneId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(documentRepository.findByIdForUpdate(doneId))
            .thenReturn(Optional.of(document(doneId, DocumentStatus.DONE)));
        when(documentRepository.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        assertThat(statusService.markProcessing(doneId))
            .isEqualTo(IngestionStatusService.ProcessingDecision.ALREADY_DONE);
        assertThat(statusService.markProcessing(missingId))
            .isEqualTo(IngestionStatusService.ProcessingDecision.MISSING);
    }

    @Test
    void markFailed_locksRowAndDoesNotOverwriteCompletedDocument() {
        UUID documentId = UUID.randomUUID();
        Document done = document(documentId, DocumentStatus.DONE);
        when(documentRepository.findByIdForUpdate(documentId)).thenReturn(Optional.of(done));

        assertThat(statusService.markFailed(documentId, "late failure")).isTrue();
        assertThat(done.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(done.getErrorMessage()).isNull();
    }

    @Test
    void markFailed_reportsDeletedDocument() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdForUpdate(documentId)).thenReturn(Optional.empty());

        assertThat(statusService.markFailed(documentId, "late failure")).isFalse();
    }

    private Document document(UUID id, DocumentStatus status) {
        Document document = new Document();
        document.setId(id);
        document.setStatus(status);
        return document;
    }
}
