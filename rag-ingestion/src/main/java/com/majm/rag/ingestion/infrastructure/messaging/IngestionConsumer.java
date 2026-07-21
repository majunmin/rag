package com.majm.rag.ingestion.infrastructure.messaging;

import com.majm.rag.ingestion.application.IngestionProcessor;
import com.majm.rag.ingestion.application.IngestionStatusService;
import com.majm.rag.ingestion.domain.IngestionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionConsumer {

    private final IngestionStatusService statusService;
    private final IngestionProcessor processor;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    public void consume(IngestionMessage message) {
        log.info("Ingesting document {}", message.documentId());

        var decision = statusService.markProcessing(message.documentId());
        if (decision != IngestionStatusService.ProcessingDecision.READY) {
            log.info("Skipping ingestion for document {}: {}", message.documentId(), decision);
            return;
        }

        try {
            var result = processor.process(message);
            log.info("Ingestion result for document {}: {}", message.documentId(), result);
        } catch (Exception e) {
            log.error("Ingestion failed for document {}", message.documentId(), e);
            statusService.markFailed(message.documentId(), e.getMessage());
            throw e;
        }
    }
}
