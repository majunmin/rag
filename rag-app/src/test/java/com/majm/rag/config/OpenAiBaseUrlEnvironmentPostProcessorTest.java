package com.majm.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiBaseUrlEnvironmentPostProcessorTest {

    private final OpenAiBaseUrlEnvironmentPostProcessor processor =
        new OpenAiBaseUrlEnvironmentPostProcessor();

    @Test
    void preservesV1WithoutDuplicatingItForOpenAiSdk() {
        assertThat(OpenAiBaseUrlEnvironmentPostProcessor.normalize(
            "https://ai-gateway.kapi.work/openai/qwen/v1"))
            .isEqualTo("https://ai-gateway.kapi.work/openai/qwen/v1");
        assertThat(OpenAiBaseUrlEnvironmentPostProcessor.normalize(
            "https://ai-gateway.kapi.work/openai/qwen/v1/"))
            .isEqualTo("https://ai-gateway.kapi.work/openai/qwen/v1");
    }

    @Test
    void addsV1ToLegacyGatewayRoots() {
        assertThat(OpenAiBaseUrlEnvironmentPostProcessor.normalize(
            "https://dashscope.aliyuncs.com/compatible-mode"))
            .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(OpenAiBaseUrlEnvironmentPostProcessor.normalize("http://localhost:8080/v10"))
            .isEqualTo("http://localhost:8080/v10/v1");
    }

    @Test
    void normalizesGlobalAndModelSpecificPropertiesBeforeBinding() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.base-url", "https://gateway.example/openai/v1")
            .withProperty("spring.ai.openai.chat.base-url", "https://chat.example/v1/")
            .withProperty("spring.ai.openai.embedding.base-url", "https://embedding.example/v1");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.base-url"))
            .isEqualTo("https://gateway.example/openai/v1");
        assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
            .isEqualTo("https://chat.example/v1");
        assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
            .isEqualTo("https://embedding.example/v1");
    }

    @Test
    void isRegisteredForApplicationBootstrap() throws IOException {
        var factories = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("META-INF/spring.factories"));

        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
            .contains(OpenAiBaseUrlEnvironmentPostProcessor.class.getName());
    }
}
