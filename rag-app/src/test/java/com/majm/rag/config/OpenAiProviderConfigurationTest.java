package com.majm.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(OpenAiPropertiesConfig.class);

    @Test
    void keepsEmbeddingOnDashScopeWhenChatUsesAKapiGateway() {
        contextRunner
            .withPropertyValues(
                "BAILIAN_BASE_URL=https://ai-gateway.kapi.work/openai/qwen",
                "BAILIAN_CHAT_API_KEY=chat-key",
                "BAILIAN_EMBEDDING_API_KEY=embedding-key")
            .run(context -> {
                OpenAiChatProperties chat = context.getBean(OpenAiChatProperties.class);
                OpenAiEmbeddingProperties embedding = context.getBean(OpenAiEmbeddingProperties.class);

                assertThat(chat.getBaseUrl())
                    .isEqualTo("https://ai-gateway.kapi.work/openai/qwen");
                assertThat(chat.getApiKey()).isEqualTo("chat-key");
                assertThat(embedding.getBaseUrl())
                    .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
                assertThat(embedding.getApiKey()).isEqualTo("embedding-key");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        OpenAiConnectionProperties.class,
        OpenAiChatProperties.class,
        OpenAiEmbeddingProperties.class
    })
    static class OpenAiPropertiesConfig {
    }
}
