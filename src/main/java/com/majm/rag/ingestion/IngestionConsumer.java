package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionConsumer {

    private static final int MIN_CHUNK_SIZE = 5;
    private static final int MAX_CHUNK_SIZE = 10000;
    private static final boolean KEEP_SEPARATOR = true;

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseService kbService;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    @Transactional
    public void consume(IngestionMessage message) {
        log.info("Ingesting document {}", message.documentId());

        Document doc = documentRepository.findById(message.documentId())
            .orElseThrow(() -> new IllegalStateException("Document not found: " + message.documentId()));
        KnowledgeBase kb = kbService.getById(message.knowledgeBaseId());

        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        try {
            DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
            List<org.springframework.ai.document.Document> rawDocs = reader.get();

            TokenTextSplitter splitter = new TokenTextSplitter(kb.getChunkSize(), kb.getChunkOverlap(),
                MIN_CHUNK_SIZE, MAX_CHUNK_SIZE, KEEP_SEPARATOR);
            List<org.springframework.ai.document.Document> chunks = splitter.apply(rawDocs);

            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).getMetadata().putAll(Map.of(
                    "knowledge_base_id", kb.getId().toString(),
                    "document_id", doc.getId().toString(),
                    "document_name", doc.getName(),
                    "chunk_index", i
                ));
            }

            // NOTE: vectorStore.add() is not part of the JPA transaction.
            // If the transaction rolls back after this point, orphan vectors may remain.
            // This implements at-least-once semantics: reprocessing will create duplicates.
            vectorStore.add(chunks);

            doc.setStatus(DocumentStatus.DONE);
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);
            log.info("Ingestion complete for document {}: {} chunks", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for document {}", doc.getId(), e);
            saveFailureStatus(doc.getId(), e.getMessage());
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void saveFailureStatus(java.util.UUID documentId, String errorMessage) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage(errorMessage);
        documentRepository.save(doc);
    }
}
