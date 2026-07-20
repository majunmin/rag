package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionConsumerTest {

    @Mock private IngestionStatusService statusService;
    @Mock private IngestionProcessor processor;

    @InjectMocks private IngestionConsumer consumer;

    @Test
    void consume_processesAClaimedDocument() {
        IngestionMessage message = message();
        when(statusService.markProcessing(message.documentId()))
            .thenReturn(IngestionStatusService.ProcessingDecision.READY);
        when(processor.process(message)).thenReturn(IngestionProcessor.Result.COMPLETED);

        consumer.consume(message);

        verify(processor).process(message);
        verify(statusService, never()).markFailed(message.documentId(), null);
    }

    @Test
    void consume_skipsCompletedOrDeletedDocuments() {
        IngestionMessage completed = message();
        IngestionMessage deleted = message();
        when(statusService.markProcessing(completed.documentId()))
            .thenReturn(IngestionStatusService.ProcessingDecision.ALREADY_DONE);
        when(statusService.markProcessing(deleted.documentId()))
            .thenReturn(IngestionStatusService.ProcessingDecision.MISSING);

        consumer.consume(completed);
        consumer.consume(deleted);

        verifyNoInteractions(processor);
    }

    @Test
    void consume_marksFailureAfterProcessingTransactionRollsBack() {
        IngestionMessage message = message();
        when(statusService.markProcessing(message.documentId()))
            .thenReturn(IngestionStatusService.ProcessingDecision.READY);
        when(processor.process(message)).thenThrow(new IllegalStateException("embedding failed"));

        assertThatThrownBy(() -> consumer.consume(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("embedding failed");

        verify(statusService).markFailed(message.documentId(), "embedding failed");
    }

    private IngestionMessage message() {
        return new IngestionMessage(UUID.randomUUID(), UUID.randomUUID());
    }
}
