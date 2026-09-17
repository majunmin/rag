package com.majm.rag.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI 2 uses the OpenAI SDK, whose base URL includes /v1.
 * Preserve that suffix and add it for legacy gateway roots accepted by Spring AI 1.
 */
public final class OpenAiBaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "normalizedOpenAiBaseUrls";
    private static final String[] BASE_URL_PROPERTIES = {
        "spring.ai.openai.base-url",
        "spring.ai.openai.chat.base-url",
        "spring.ai.openai.embedding.base-url"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> normalizedProperties = new LinkedHashMap<>();
        for (String property : BASE_URL_PROPERTIES) {
            String configured = environment.getProperty(property);
            String normalized = normalize(configured);
            if (configured != null && !configured.equals(normalized)) {
                normalizedProperties.put(property, normalized);
            }
        }

        if (!normalizedProperties.isEmpty()) {
            environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, normalizedProperties));
        }
    }

    static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/v1")) {
            normalized += "/v1";
        }
        return normalized;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
