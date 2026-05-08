package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionConsumer {

    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int MAX_NUM_CHUNKS = 10000;
    private static final boolean KEEP_SEPARATOR = true;

    private final KnowledgeBaseService kbService;
    private final IngestionStatusService statusService;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    public void consume(IngestionMessage message) {
        log.info("Ingesting document {}", message.documentId());

        Document doc = statusService.markProcessing(message.documentId());
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

        // Note: Spring AI's TokenTextSplitter has no "chunk overlap" concept.
        // KnowledgeBase.chunkOverlap is mapped to minChunkSizeChars, preserving
        // the (slightly off) behavior from the project's 1.0 days. True overlap
        // would require a custom splitter wrapper; tracked as future work.
        TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(kb.getChunkSize())
            .withMinChunkSizeChars(kb.getChunkOverlap())
            .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
            .withMaxNumChunks(MAX_NUM_CHUNKS)
            .withKeepSeparator(KEEP_SEPARATOR)
            .build();
        List<org.springframework.ai.document.Document> chunks = splitter.apply(rawDocs);

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().putAll(Map.of(
                "knowledge_base_id", kb.getId().toString(),
                "document_id", doc.getId().toString(),
                "document_name", doc.getName(),
                "chunk_index", i
            ));
        }

        vectorStore.add(chunks);
        return chunks.size();
    }
}
