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
    public Document markProcessing(UUID documentId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        doc.setStatus(DocumentStatus.PROCESSING);
        return doc;
    }

    @Transactional
    public void markDone(UUID documentId, int chunkCount) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        doc.setStatus(DocumentStatus.DONE);
        doc.setChunkCount(chunkCount);
        doc.setErrorMessage(null);
    }

    @Transactional
    public void markFailed(UUID documentId, String errorMessage) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage(errorMessage);
    }
}
