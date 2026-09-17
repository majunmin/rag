package com.majm.rag.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

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
                    .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
                assertThat(embedding.getApiKey()).isEqualTo("embedding-key");
            });
    }

    @Test
    void openAiSdkUsesSeparateGatewayPathsKeysAndModels() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(post(urlEqualTo("/openai/qwen/v1/chat/completions"))
                .willReturn(okJson("""
                    {"id":"chat-test","object":"chat.completion","created":1,"model":"qwen-plus",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},
                                 "finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """)));
            server.stubFor(post(urlEqualTo("/compatible-mode/v1/embeddings"))
                .willReturn(okJson("""
                    {"object":"list","model":"text-embedding-v3",
                     "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
                     "usage":{"prompt_tokens":1,"total_tokens":1}}
                    """)));

            contextRunner
                .withPropertyValues(
                    "BAILIAN_CHAT_BASE_URL=" + server.baseUrl() + "/openai/qwen",
                    "BAILIAN_EMBEDDING_BASE_URL=" + server.baseUrl() + "/compatible-mode/v1/",
                    "BAILIAN_CHAT_API_KEY=chat-key",
                    "BAILIAN_EMBEDDING_API_KEY=embedding-key",
                    "spring.ai.openai.max-retries=0",
                    "spring.ai.openai.embedding.encoding-format=float")
                .withInitializer(context -> new OpenAiBaseUrlEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), null))
                .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                .withConfiguration(AutoConfigurations.of(
                    OpenAiChatAutoConfiguration.class, OpenAiEmbeddingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenAiChatModel.class).call("hi")).isEqualTo("hello");
                    assertThat(context.getBean(OpenAiEmbeddingModel.class).embed("hi"))
                        .containsExactly(0.1f, 0.2f);
                    server.stubFor(post(urlEqualTo("/openai/qwen/v1/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                        .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                            .withBody("""
                                data: {"id":"stream-test","object":"chat.completion.chunk","created":1,"model":"qwen-plus","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"}}]}

                                data: {"id":"stream-test","object":"chat.completion.chunk","created":1,"model":"qwen-plus","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));
                    assertThat(context.getBean(OpenAiChatModel.class).stream("hi")
                        .collectList().block(Duration.ofSeconds(10)))
                        .contains("hello");
                });

            server.verify(postRequestedFor(urlEqualTo("/openai/qwen/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer chat-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("qwen-plus"))));
            server.verify(postRequestedFor(urlEqualTo("/compatible-mode/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer embedding-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-v3"))));
        } finally {
            server.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        OpenAiCommonProperties.class,
        OpenAiChatProperties.class,
        OpenAiEmbeddingProperties.class
    })
    static class OpenAiPropertiesConfig {
    }
}
