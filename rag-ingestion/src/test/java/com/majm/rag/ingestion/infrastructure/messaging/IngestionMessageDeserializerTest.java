package com.majm.rag.ingestion.infrastructure.messaging;

import com.majm.rag.ingestion.domain.IngestionMessage;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionMessageDeserializerTest {

    private static final String TOPIC = "document.ingestion";

    @Test
    void ignoresLegacyJavaTypeHeaderAfterPackageMove() {
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__",
            "com.majm.rag.ingestion.dto.IngestionMessage".getBytes(StandardCharsets.UTF_8));

        try (ErrorHandlingDeserializer<IngestionMessage> deserializer = deserializer()) {
            IngestionMessage result = deserializer.deserialize(TOPIC, headers, payload(documentId, knowledgeBaseId));

            assertThat(result).isEqualTo(new IngestionMessage(documentId, knowledgeBaseId));
            assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNull();
        }
    }

    @Test
    void capturesMalformedPayloadForTheContainerErrorHandler() {
        RecordHeaders headers = new RecordHeaders();

        try (ErrorHandlingDeserializer<IngestionMessage> deserializer = deserializer()) {
            IngestionMessage result = deserializer.deserialize(
                TOPIC, headers, "{not-json".getBytes(StandardCharsets.UTF_8));

            assertThat(result).isNull();
            assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
        }
    }

    private ErrorHandlingDeserializer<IngestionMessage> deserializer() {
        ErrorHandlingDeserializer<IngestionMessage> deserializer =
            new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>());
        deserializer.configure(Map.of(
            JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, IngestionMessage.class.getName(),
            JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false,
            JacksonJsonDeserializer.TRUSTED_PACKAGES, IngestionMessage.class.getPackageName()), false);
        return deserializer;
    }

    private byte[] payload(UUID documentId, UUID knowledgeBaseId) {
        String json = "{\"documentId\":\"%s\",\"knowledgeBaseId\":\"%s\"}"
            .formatted(documentId, knowledgeBaseId);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
