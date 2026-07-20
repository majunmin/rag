package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class IngestionOutboxPublisher {

    private final IngestionOutboxRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;
    private final int batchSize;
    private final long sendTimeoutMs;

    public IngestionOutboxPublisher(
        IngestionOutboxRepository repository,
        KafkaTemplate<String, Object> kafkaTemplate,
        @Value("${app.ingestion.topic:document.ingestion}") String topic,
        @Value("${app.ingestion.outbox.batch-size:20}") int batchSize,
        @Value("${app.ingestion.outbox.send-timeout-ms:10000}") long sendTimeoutMs
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = Math.clamp(batchSize, 1, 100);
        this.sendTimeoutMs = Math.max(100L, sendTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${app.ingestion.outbox.delay-ms:1000}")
    @Transactional
    public void publishReady() {
        for (IngestionOutboxEvent event : repository.lockReadyBatch(batchSize)) {
            try {
                kafkaTemplate.send(
                        topic,
                        event.documentId().toString(),
                        new IngestionMessage(event.documentId(), event.knowledgeBaseId()))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                repository.markPublished(event.id());
            } catch (Exception error) {
                int nextAttempt = event.attemptCount() + 1;
                Instant nextAttemptAt = Instant.now()
                    .plus(retryDelaySeconds(nextAttempt), ChronoUnit.SECONDS);
                String message = StringUtils.abbreviate(rootMessage(error), 4000);
                repository.markRetry(event.id(), nextAttempt, nextAttemptAt, message);
                log.warn("Could not publish ingestion outbox event {} (attempt {}): {}",
                    event.id(), nextAttempt, message);
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private long retryDelaySeconds(int attempt) {
        return Math.min(300L, 1L << Math.min(attempt, 8));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.defaultIfBlank(current.getMessage(), current.getClass().getSimpleName());
    }
}
