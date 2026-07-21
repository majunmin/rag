package com.majm.rag.ingestion.infrastructure.messaging;

import com.majm.rag.ingestion.domain.IngestionOutboxEvent;
import com.majm.rag.ingestion.infrastructure.persistence.IngestionOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionOutboxPublisherTest {

    @Mock private IngestionOutboxRepository repository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private IngestionOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new IngestionOutboxPublisher(
            repository, kafkaTemplate, "document.ingestion", 20, 10_000L);
    }

    @Test
    void publishReady_marksEventPublishedAfterBrokerAcknowledgement() {
        IngestionOutboxEvent event = event(0);
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(repository.lockReadyBatch(20)).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("document.ingestion"), eq(event.documentId().toString()), any()))
            .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishReady();

        verify(repository).markPublished(event.id());
    }

    @Test
    void publishReady_schedulesRetryWhenBrokerRejectsEvent() {
        IngestionOutboxEvent event = event(2);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(repository.lockReadyBatch(20)).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("document.ingestion"), eq(event.documentId().toString()), any()))
            .thenReturn(failed);

        publisher.publishReady();

        verify(repository).markRetry(
            eq(event.id()), eq(3), any(Instant.class), contains("broker down"));
    }

    private IngestionOutboxEvent event(int attemptCount) {
        return new IngestionOutboxEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), attemptCount);
    }
}
