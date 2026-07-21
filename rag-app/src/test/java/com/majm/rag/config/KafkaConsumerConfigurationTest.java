package com.majm.rag.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(KafkaPropertiesConfig.class);

    @Test
    void usesJacksonJsonWithoutJavaTypeHeaderCoupling() {
        contextRunner.run(context -> {
            KafkaProperties kafka = context.getBean(KafkaProperties.class);

            assertThat(kafka.buildConsumerProperties(null))
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                    "org.apache.kafka.common.serialization.StringDeserializer")
                .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName())
                .containsEntry(JsonDeserializer.VALUE_DEFAULT_TYPE,
                    "com.majm.rag.ingestion.domain.IngestionMessage")
                .containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false")
                .containsEntry(JsonDeserializer.TRUSTED_PACKAGES, "com.majm.rag.ingestion.domain");

            assertThat(kafka.buildProducerProperties(null))
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, "false");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class KafkaPropertiesConfig {
    }
}
