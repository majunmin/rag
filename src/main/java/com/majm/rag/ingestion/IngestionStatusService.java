package com.majm.rag.ingestion;

import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionStatusService {

    private final DocumentRepository documentRepository;

    @Transactional
    public ProcessingDecision markProcessing(UUID documentId) {
        var document = documentRepository.findByIdForUpdate(documentId);
        if (document.isEmpty()) {
            return ProcessingDecision.MISSING;
        }
        Document doc = document.get();
        if (doc.getStatus() == DocumentStatus.DONE) {
            return ProcessingDecision.ALREADY_DONE;
        }
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setErrorMessage(null);
        return ProcessingDecision.READY;
    }

    @Transactional
    public boolean markFailed(UUID documentId, String errorMessage) {
        var document = documentRepository.findByIdForUpdate(documentId);
        if (document.isEmpty()) {
            return false;
        }
        Document doc = document.get();
        if (doc.getStatus() == DocumentStatus.DONE) {
            return true;
        }
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage(errorMessage);
        return true;
    }

    public enum ProcessingDecision {
        READY,
        ALREADY_DONE,
        MISSING
    }
}
