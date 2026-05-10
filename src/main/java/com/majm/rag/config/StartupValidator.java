package com.majm.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupValidator {

    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");
    private static final Set<String> FORBIDDEN_API_KEYS = Set.of("", "dummy", "test", "changeme");
    private static final String DEFAULT_DEV_DB_PASSWORD = "rag";

    private final Environment environment;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @PostConstruct
    void validate() {
        boolean isProd = Arrays.stream(environment.getActiveProfiles())
            .map(StringUtils::lowerCase)
            .anyMatch(PROD_PROFILES::contains);

        if (!isProd) {
            log.info("StartupValidator: non-prod profile, skipping strict checks");
            return;
        }

        String normalizedKey = StringUtils.lowerCase(StringUtils.trimToEmpty(openAiApiKey));
        if (FORBIDDEN_API_KEYS.contains(normalizedKey)) {
            throw new IllegalStateException(
                "Refusing to start in prod profile: spring.ai.openai.api-key is empty or a known placeholder. "
                    + "Set DASHSCOPE_API_KEY or OPENAI_API_KEY to a real value.");
        }

        if (StringUtils.equals(dbPassword, DEFAULT_DEV_DB_PASSWORD)) {
            throw new IllegalStateException(
                "Refusing to start in prod profile: spring.datasource.password is the dev default. "
                    + "Set DB_PASSWORD to a real value.");
        }

        log.info("StartupValidator: prod profile checks passed");
    }
}
