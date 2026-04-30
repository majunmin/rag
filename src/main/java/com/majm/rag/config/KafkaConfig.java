package com.majm.rag.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Value("${app.ingestion.topic:document.ingestion}")
    private String ingestionTopic;

    @Value("${app.ingestion.dlt-topic:document.ingestion.dlt}")
    private String dltTopic;

    @Bean
    public NewTopic ingestionTopic() {
        return TopicBuilder.name(ingestionTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dltTopic() {
        return TopicBuilder.name(dltTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler errorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(3);
        return new DefaultErrorHandler(backOff);
    }
}
