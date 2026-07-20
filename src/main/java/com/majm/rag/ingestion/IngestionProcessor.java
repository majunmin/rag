package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.ChunkQueryService;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionProcessor {

    private final DocumentRepository documentRepository;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;
    private final ChunkQueryService chunkQueryService;

    @Transactional
    public Result process(IngestionMessage message) {
        var document = documentRepository.findByIdForUpdate(message.documentId());
        if (document.isEmpty()) {
            return Result.MISSING;
        }

        Document doc = document.get();
        if (doc.getStatus() == DocumentStatus.DONE) {
            return Result.ALREADY_DONE;
        }

        KnowledgeBase kb = doc.getKnowledgeBase();
        if (kb == null || !Objects.equals(kb.getId(), message.knowledgeBaseId())) {
            throw new IllegalArgumentException(
                "Document does not belong to knowledge base " + message.knowledgeBaseId());
        }

        int chunkCount = ingest(doc, kb);
        doc.setStatus(DocumentStatus.DONE);
        doc.setChunkCount(chunkCount);
        doc.setErrorMessage(null);
        return Result.COMPLETED;
    }

    private int ingest(Document doc, KnowledgeBase kb) {
        DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
        List<org.springframework.ai.document.Document> rawDocs = reader.get();

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

        vectorStore.add(indexedChunks);
        chunkQueryService.deleteByDocumentFromIndex(doc.getId(), indexedChunks.size());
        return indexedChunks.size();
    }

    public enum Result {
        COMPLETED,
        ALREADY_DONE,
        MISSING
    }
}
