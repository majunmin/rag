package com.majm.rag.ingestion;

import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionStatusService {

    private final DocumentRepository documentRepository;

    @Transactional
    public Optional<Document> markProcessing(UUID documentId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        if (doc.getStatus() == DocumentStatus.DONE) {
            return Optional.empty();
        }
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setErrorMessage(null);
        return Optional.of(doc);
    }

    @Transactional
    public boolean markDone(UUID documentId, int chunkCount) {
        Optional<Document> document = documentRepository.findById(documentId);
        if (document.isEmpty()) {
            return false;
        }
        Document doc = document.get();
        doc.setStatus(DocumentStatus.DONE);
        doc.setChunkCount(chunkCount);
        doc.setErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean markFailed(UUID documentId, String errorMessage) {
        Optional<Document> document = documentRepository.findById(documentId);
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
}
