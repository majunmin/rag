package com.majm.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("RAG System API")
                .description("Spring AI based RAG (Retrieval-Augmented Generation) system. " +
                    "Manage knowledge bases, ingest documents asynchronously via Kafka, " +
                    "and chat with LLM grounded in your knowledge base via SSE streaming.")
                .version("v1.0.0")
                .contact(new Contact().name("majm").email("majm@example.com"))
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local development")
            ));
    }
}
