package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionEventListener {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.ingestion.topic:document.ingestion}")
    private String ingestionTopic;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestionRequested(IngestionRequestedEvent event) {
        log.info("Publishing ingestion message for document {}", event.documentId());
        kafkaTemplate.send(ingestionTopic, event.documentId().toString(),
            new IngestionMessage(event.documentId(), event.knowledgeBaseId()));
    }
}
