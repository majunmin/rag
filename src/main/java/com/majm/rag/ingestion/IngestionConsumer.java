package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.ChunkQueryService;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionConsumer {

    private final KnowledgeBaseService kbService;
    private final IngestionStatusService statusService;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;
    private final ChunkQueryService chunkQueryService;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    public void consume(IngestionMessage message) {
        log.info("Ingesting document {}", message.documentId());

        var processing = statusService.markProcessing(message.documentId());
        if (processing.isEmpty()) {
            log.info("Skipping duplicate ingestion message for completed document {}", message.documentId());
            return;
        }
        Document doc = processing.get();
        KnowledgeBase kb = kbService.getById(message.knowledgeBaseId());

        try {
            int chunkCount = ingest(doc, kb);
            statusService.markDone(doc.getId(), chunkCount);
            log.info("Ingestion complete for document {}: {} chunks", doc.getId(), chunkCount);
        } catch (Exception e) {
            log.error("Ingestion failed for document {}", doc.getId(), e);
            statusService.markFailed(doc.getId(), e.getMessage());
            throw e;
        }
    }

    private int ingest(Document doc, KnowledgeBase kb) {
        DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
        List<org.springframework.ai.document.Document> rawDocs = reader.get();

        // Custom splitter: token-based with sliding-window overlap, controlled
        // by KnowledgeBase.chunkSize / chunkOverlap. See OverlappingTokenTextSplitter.
        var splitter = new OverlappingTokenTextSplitter(kb.getChunkSize(), kb.getChunkOverlap());
        List<org.springframework.ai.document.Document> chunks = splitter.apply(rawDocs);
        List<org.springframework.ai.document.Document> indexedChunks = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>(chunks.get(i).getMetadata());
            metadata.putAll(Map.of(
                "knowledge_base_id", kb.getId().toString(),
                "document_id", doc.getId().toString(),
                "document_name", doc.getName(),
                "chunk_index", i
            ));
            String chunkId = UUID.nameUUIDFromBytes(
                (doc.getId() + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            indexedChunks.add(new org.springframework.ai.document.Document(
                chunkId, chunks.get(i).getText(), metadata));
        }

        chunkQueryService.deleteByDocument(doc.getId());
        vectorStore.add(indexedChunks);
        return indexedChunks.size();
    }
}
