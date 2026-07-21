package com.majm.rag.retrieval.config;

import com.majm.rag.retrieval.rerank.BailianRerankService;
import com.majm.rag.retrieval.rerank.NoOpRerankService;
import com.majm.rag.retrieval.rerank.RerankService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the reranker bean. Picks {@link BailianRerankService} when
 * {@code app.rerank.enabled=true}, otherwise {@link NoOpRerankService}
 * (recall-only mode).
 */
@Configuration
public class RerankConfig {

    @Bean
    RerankService rerankService(
        @Value("${app.rerank.enabled:true}") boolean enabled,
        @Value("${app.rerank.base-url}") String baseUrl,
        @Value("${app.rerank.api-key:}") String apiKey,
        @Value("${app.rerank.model:gte-rerank-v2}") String model,
        @Value("${app.rerank.timeout-ms:3000}") long timeoutMs
    ) {
        if (!enabled) {
            return new NoOpRerankService();
        }
        return new BailianRerankService(baseUrl, apiKey, model, Duration.ofMillis(timeoutMs));
    }
}
