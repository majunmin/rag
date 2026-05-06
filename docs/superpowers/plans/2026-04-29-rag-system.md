# RAG System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot + Spring AI RAG system with knowledge base management, async document ingestion via Kafka, pgvector retrieval, and SSE-streaming chat API supporting multiple LLM providers.

**Architecture:** Single Spring Boot 3.x application with four modules (knowledge, ingestion, retrieval, chat). Documents are uploaded via REST, published to a Kafka topic `document.ingestion`, then parsed/chunked/embedded asynchronously by a `@KafkaListener` consumer and stored in PostgreSQL with pgvector. Chat queries embed the user question, retrieve Top-K chunks filtered by knowledge base, and stream the LLM response via SSE.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI 2.0, PostgreSQL 18 + pgvector, Kafka, Apache Tika, Flyway, Maven, JUnit 5 + Testcontainers

---

## File Map

```
rag0429/
├── pom.xml
├── docker-compose.yml
└── src/
    ├── main/
    │   ├── java/com/majm/rag/
    │   │   ├── RagApplication.java
    │   │   ├── config/
    │   │   │   ├── KafkaConfig.java
    │   │   │   ├── VectorStoreConfig.java
    │   ��   │   └── LlmConfig.java
    │   │   ├── knowledge/
    │   │   │   ├── domain/
    │   │   │   │   ├── KnowledgeBase.java
    │   │   │   │   ├── KnowledgeBaseStatus.java
    │   │   │   │   ├── Document.java
    │   │   │   │   ├── DocumentStatus.java
    │   │   │   │   └── DocumentChunk.java
    │   │   │   ├── KnowledgeBaseRepository.java
    │   │   │   ├── DocumentRepository.java
    │   │   │   ├── DocumentChunkRepository.java
    │   │   │   ├── KnowledgeBaseService.java
    │   │   │   ├── KnowledgeBaseController.java
    │   │   │   └── dto/
    │   │   │       ├── CreateKnowledgeBaseRequest.java
    │   │   │       ├── UpdateKnowledgeBaseRequest.java
    │   │   │       └── KnowledgeBaseResponse.java
    │   │   ├── ingestion/
    │   │   │   ├── StorageService.java           (interface)
    │   │   │   ├── LocalStorageService.java
    │   │   │   ├── DocumentParserFactory.java
    │   │   ��   ├── DocumentUploadService.java
    │   │   │   ├── IngestionConsumer.java
    │   │   │   └── dto/
    │   │   │       ├── UploadDocumentResponse.java
    │   │   │       └── IngestionMessage.java
    │   │   ├── retrieval/
    │   │   │   ├── ModelRouter.java
    │   │   │   └── RetrievalService.java
    │   │   └── chat/
    │   │       ├── domain/
    │   │       │   └── Conversation.java
    │   │       ├── ConversationRepository.java
    │   │       ├── ChatService.java
    │   │       ├── ChatController.java
    │   │       └── dto/
    │   │           ├── ChatRequest.java
    │   │           ├── CreateConversationRequest.java
    │   │           └── ConversationMessageRequest.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql
    │           └── V2__create_hnsw_index.sql
    └── test/
        └── java/com/majm/rag/
            ├── knowledge/
            │   └── KnowledgeBaseServiceTest.java
            ├── ingestion/
            │   ├── DocumentParserFactoryTest.java
            │   └── DocumentUploadServiceTest.java
            └── chat/
                └── ChatServiceTest.java
```

---

## Task 1: Project Scaffold + Dependencies

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/majm/rag/RagApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `docker-compose.yml`

- [ ] **Step 1: Create Maven project structure**

```bash
mkdir -p rag0429/src/main/java/com/majm/rag
mkdir -p rag0429/src/main/resources/db/migration
mkdir -p rag0429/src/test/java/com/majm/rag
cd rag0429
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.majm</groupId>
    <artifactId>rag</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>rag</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Spring AI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-tika-document-reader</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- Kafka -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Utils -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create `RagApplication.java`**

```java
// src/main/java/com/majm/rag/RagApplication.java
package com.majm.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `docker-compose.yml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg18
    environment:
      POSTGRES_DB: rag
      POSTGRES_USER: rag
      POSTGRES_PASSWORD: rag
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  kafka:
    image: apache/kafka:3.8.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"

volumes:
  pgdata:
```

- [ ] **Step 5: Create `application.yml`**

```yaml
spring:
  application:
    name: rag
  datasource:
    url: jdbc:postgresql://localhost:5432/rag
    username: rag
    password: rag
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: rag-ingestion
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.majm.rag.*"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        initialize-schema: false
    openai:
      api-key: ${OPENAI_API_KEY:dummy}
      chat:
        options:
          model: gpt-4o-mini
      embedding:
        options:
          model: text-embedding-3-small
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        options:
          model: llama3.2

app:
  storage:
    base-path: /data/uploads
  ingestion:
    topic: document.ingestion
    dlt-topic: document.ingestion.dlt

server:
  port: 8080
```

- [ ] **Step 6: Start infrastructure and verify connectivity**

```bash
docker compose up -d
# Wait ~10 seconds for services to start
docker compose ps
# Expected: postgres and kafka both "running"
```

- [ ] **Step 7: Verify Maven compiles**

```bash
mvn compile
# Expected: BUILD SUCCESS
```

- [ ] **Step 8: Commit**

```bash
git init
git add pom.xml docker-compose.yml src/
git commit -m "feat: project scaffold with Spring Boot 3.3 + Spring AI 2.0"
```

---

## Task 2: Database Schema (Flyway Migrations)

**Files:**
- Create: `src/main/resources/db/migration/V1__init_schema.sql`
- Create: `src/main/resources/db/migration/V2__create_hnsw_index.sql`

- [ ] **Step 1: Create `V1__init_schema.sql`**

```sql
-- src/main/resources/db/migration/V1__init_schema.sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    embedding_model  VARCHAR(100) NOT NULL DEFAULT 'text-embedding-3-small',
    chunk_size       INT NOT NULL DEFAULT 512,
    chunk_overlap    INT NOT NULL DEFAULT 64,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE document (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    file_type         VARCHAR(20) NOT NULL,
    file_path         VARCHAR(1024),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    chunk_count       INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE document_chunk (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id       UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL,
    content           TEXT NOT NULL,
    metadata          JSONB,
    embedding         VECTOR(1536),
    chunk_index       INT NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_kb ON document(knowledge_base_id);
CREATE INDEX idx_chunk_kb    ON document_chunk(knowledge_base_id);
CREATE INDEX idx_chunk_doc   ON document_chunk(document_id);

CREATE TABLE conversation (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    messages          JSONB NOT NULL DEFAULT '[]',
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
```

- [ ] **Step 2: Create `V2__create_hnsw_index.sql`**

```sql
-- src/main/resources/db/migration/V2__create_hnsw_index.sql
-- Separate migration: HNSW index creation can be slow on large tables
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

- [ ] **Step 3: Run migrations against running Postgres**

```bash
mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/rag \
    -Dflyway.user=rag -Dflyway.password=rag
# Expected: Successfully applied 2 migrations
```

- [ ] **Step 4: Verify schema**

```bash
docker exec -it rag0429-postgres-1 psql -U rag -d rag -c "\dt"
# Expected: knowledge_base, document, document_chunk, conversation listed
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/
git commit -m "feat: flyway migrations for knowledge_base, document, chunk, conversation"
```

---

## Task 3: Domain Entities + Repositories

**Files:**
- Create: `src/main/java/com/majm/rag/knowledge/domain/KnowledgeBase.java`
- Create: `src/main/java/com/majm/rag/knowledge/domain/KnowledgeBaseStatus.java`
- Create: `src/main/java/com/majm/rag/knowledge/domain/Document.java`
- Create: `src/main/java/com/majm/rag/knowledge/domain/DocumentStatus.java`
- Create: `src/main/java/com/majm/rag/knowledge/domain/DocumentChunk.java`
- Create: `src/main/java/com/majm/rag/knowledge/KnowledgeBaseRepository.java`
- Create: `src/main/java/com/majm/rag/knowledge/DocumentRepository.java`
- Create: `src/main/java/com/majm/rag/knowledge/DocumentChunkRepository.java`
- Create: `src/main/java/com/majm/rag/chat/domain/Conversation.java`
- Create: `src/main/java/com/majm/rag/chat/ConversationRepository.java`

- [ ] **Step 1: Create enums**

```java
// src/main/java/com/majm/rag/knowledge/domain/KnowledgeBaseStatus.java
package com.majm.rag.knowledge.domain;

public enum KnowledgeBaseStatus { ACTIVE, ARCHIVED }
```

```java
// src/main/java/com/majm/rag/knowledge/domain/DocumentStatus.java
package com.majm.rag.knowledge.domain;

public enum DocumentStatus { PENDING, PROCESSING, DONE, FAILED }
```

- [ ] **Step 2: Create `KnowledgeBase.java`**

```java
// src/main/java/com/majm/rag/knowledge/domain/KnowledgeBase.java
package com.majm.rag.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_base")
@Getter @Setter
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel = "text-embedding-3-small";

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 512;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 64;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Create `Document.java`**

```java
// src/main/java/com/majm/rag/knowledge/domain/Document.java
package com.majm.rag.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "document")
@Getter @Setter
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "file_path")
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "chunk_count")
    private int chunkCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Create `DocumentChunk.java`**

```java
// src/main/java/com/majm/rag/knowledge/domain/DocumentChunk.java
package com.majm.rag.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document_chunk")
@Getter @Setter
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: Create `Conversation.java`**

```java
// src/main/java/com/majm/rag/chat/domain/Conversation.java
package com.majm.rag.chat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "conversation")
@Getter @Setter
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, String>> messages = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: Create repositories**

```java
// src/main/java/com/majm/rag/knowledge/KnowledgeBaseRepository.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    Page<KnowledgeBase> findAll(Pageable pageable);
}
```

```java
// src/main/java/com/majm/rag/knowledge/DocumentRepository.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Page<Document> findByKnowledgeBaseId(UUID knowledgeBaseId, Pageable pageable);
}
```

```java
// src/main/java/com/majm/rag/knowledge/DocumentChunkRepository.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);
    void deleteByDocumentId(UUID documentId);
    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);
}
```

```java
// src/main/java/com/majm/rag/chat/ConversationRepository.java
package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
}
```

- [ ] **Step 7: Verify app starts with schema**

```bash
mvn spring-boot:run
# Expected: Started RagApplication — no Flyway or JPA errors
# Ctrl+C to stop
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/
git commit -m "feat: domain entities and repositories for knowledge base, document, chunk, conversation"
```

---

## Task 4: Knowledge Base Service + Controller

**Files:**
- Create: `src/main/java/com/majm/rag/knowledge/dto/CreateKnowledgeBaseRequest.java`
- Create: `src/main/java/com/majm/rag/knowledge/dto/UpdateKnowledgeBaseRequest.java`
- Create: `src/main/java/com/majm/rag/knowledge/dto/KnowledgeBaseResponse.java`
- Create: `src/main/java/com/majm/rag/knowledge/KnowledgeBaseService.java`
- Create: `src/main/java/com/majm/rag/knowledge/KnowledgeBaseController.java`
- Create: `src/test/java/com/majm/rag/knowledge/KnowledgeBaseServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/majm/rag/knowledge/KnowledgeBaseServiceTest.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;
import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository repository;

    @InjectMocks
    private KnowledgeBaseService service;

    private KnowledgeBase savedKb;

    @BeforeEach
    void setUp() {
        savedKb = new KnowledgeBase();
        savedKb.setId(UUID.randomUUID());
        savedKb.setName("Test KB");
        savedKb.setEmbeddingModel("text-embedding-3-small");
        savedKb.setChunkSize(512);
        savedKb.setChunkOverlap(64);
        savedKb.setStatus(KnowledgeBaseStatus.ACTIVE);
    }

    @Test
    void create_shouldPersistAndReturnKnowledgeBase() {
        when(repository.save(any())).thenReturn(savedKb);
        var request = new CreateKnowledgeBaseRequest("Test KB", "desc", "text-embedding-3-small", 512, 64);

        KnowledgeBase result = service.create(request);

        assertThat(result.getName()).isEqualTo("Test KB");
        verify(repository).save(any(KnowledgeBase.class));
    }

    @Test
    void getById_shouldThrowWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    void update_shouldModifyFieldsAndSave() {
        when(repository.findById(savedKb.getId())).thenReturn(Optional.of(savedKb));
        when(repository.save(any())).thenReturn(savedKb);
        var request = new UpdateKnowledgeBaseRequest("Updated Name", null, 256, 32);

        KnowledgeBase result = service.update(savedKb.getId(), request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getChunkSize()).isEqualTo(256);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=KnowledgeBaseServiceTest -q
# Expected: FAIL — KnowledgeBaseService, DTOs not yet created
```

- [ ] **Step 3: Create DTOs**

```java
// src/main/java/com/majm/rag/knowledge/dto/CreateKnowledgeBaseRequest.java
package com.majm.rag.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateKnowledgeBaseRequest(
    @NotBlank String name,
    String description,
    @NotBlank String embeddingModel,
    @Positive int chunkSize,
    @Positive int chunkOverlap
) {}
```

```java
// src/main/java/com/majm/rag/knowledge/dto/UpdateKnowledgeBaseRequest.java
package com.majm.rag.knowledge.dto;

public record UpdateKnowledgeBaseRequest(
    String name,
    String description,
    Integer chunkSize,
    Integer chunkOverlap
) {}
```

```java
// src/main/java/com/majm/rag/knowledge/dto/KnowledgeBaseResponse.java
package com.majm.rag.knowledge.dto;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record KnowledgeBaseResponse(
    UUID id,
    String name,
    String description,
    String embeddingModel,
    int chunkSize,
    int chunkOverlap,
    KnowledgeBaseStatus status,
    LocalDateTime createdAt
) {
    public static KnowledgeBaseResponse from(KnowledgeBase kb) {
        return new KnowledgeBaseResponse(
            kb.getId(), kb.getName(), kb.getDescription(),
            kb.getEmbeddingModel(), kb.getChunkSize(), kb.getChunkOverlap(),
            kb.getStatus(), kb.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: Create `KnowledgeBaseService.java`**

```java
// src/main/java/com/majm/rag/knowledge/KnowledgeBaseService.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.domain.KnowledgeBase;
import com.majm.rag.knowledge.domain.KnowledgeBaseStatus;
import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;

    @Transactional
    public KnowledgeBase create(CreateKnowledgeBaseRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setEmbeddingModel(request.embeddingModel());
        kb.setChunkSize(request.chunkSize());
        kb.setChunkOverlap(request.chunkOverlap());
        return repository.save(kb);
    }

    public Page<KnowledgeBase> listAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public KnowledgeBase getById(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("KnowledgeBase not found: " + id));
    }

    @Transactional
    public KnowledgeBase update(UUID id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = getById(id);
        if (request.name() != null) kb.setName(request.name());
        if (request.description() != null) kb.setDescription(request.description());
        if (request.chunkSize() != null) kb.setChunkSize(request.chunkSize());
        if (request.chunkOverlap() != null) kb.setChunkOverlap(request.chunkOverlap());
        return repository.save(kb);
    }

    @Transactional
    public void delete(UUID id) {
        KnowledgeBase kb = getById(id);
        kb.setStatus(KnowledgeBaseStatus.ARCHIVED);
        repository.delete(kb);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=KnowledgeBaseServiceTest -q
# Expected: Tests run: 3, Failures: 0, Errors: 0
```

- [ ] **Step 6: Create `KnowledgeBaseController.java`**

```java
// src/main/java/com/majm/rag/knowledge/KnowledgeBaseController.java
package com.majm.rag.knowledge;

import com.majm.rag.knowledge.dto.CreateKnowledgeBaseRequest;
import com.majm.rag.knowledge.dto.KnowledgeBaseResponse;
import com.majm.rag.knowledge.dto.UpdateKnowledgeBaseRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ResponseEntity.ok(KnowledgeBaseResponse.from(service.create(request)));
    }

    @GetMapping
    public Page<KnowledgeBaseResponse> list(Pageable pageable) {
        return service.listAll(pageable).map(KnowledgeBaseResponse::from);
    }

    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@PathVariable UUID id) {
        return KnowledgeBaseResponse.from(service.getById(id));
    }

    @PutMapping("/{id}")
    public KnowledgeBaseResponse update(@PathVariable UUID id,
                                        @RequestBody UpdateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: Smoke-test the endpoint**

```bash
# Start app (docker compose must be running)
mvn spring-boot:run &
sleep 5

# Create a knowledge base
curl -s -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -d '{"name":"Test KB","description":"test","embeddingModel":"text-embedding-3-small","chunkSize":512,"chunkOverlap":64}' | jq .
# Expected: JSON with id, name, status: "ACTIVE"

# List knowledge bases
curl -s http://localhost:8080/api/v1/knowledge-bases | jq .
# Expected: page with 1 item
```

- [ ] **Step 8: Commit**

```bash
git add src/
git commit -m "feat: knowledge base CRUD service and REST controller"
```

---

## Task 5: Storage Service + Document Upload

**Files:**
- Create: `src/main/java/com/majm/rag/ingestion/StorageService.java`
- Create: `src/main/java/com/majm/rag/ingestion/LocalStorageService.java`
- Create: `src/main/java/com/majm/rag/ingestion/dto/IngestionMessage.java`
- Create: `src/main/java/com/majm/rag/ingestion/dto/UploadDocumentResponse.java`
- Create: `src/main/java/com/majm/rag/ingestion/DocumentUploadService.java`
- Create: `src/main/java/com/majm/rag/knowledge/DocumentController.java`
- Create: `src/test/java/com/majm/rag/ingestion/DocumentUploadServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/majm/rag/ingestion/DocumentUploadServiceTest.java
package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock private KnowledgeBaseService kbService;
    @Mock private DocumentRepository documentRepository;
    @Mock private StorageService storageService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private DocumentUploadService uploadService;

    @Test
    void upload_shouldCreateDocumentAndPublishToKafka() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);

        Document savedDoc = new Document();
        savedDoc.setId(UUID.randomUUID());
        savedDoc.setStatus(DocumentStatus.PENDING);
        savedDoc.setKnowledgeBase(kb);

        when(kbService.getById(kbId)).thenReturn(kb);
        when(storageService.store(any(), any(), any())).thenReturn("/data/uploads/test.pdf");
        when(documentRepository.save(any())).thenReturn(savedDoc);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
            "application/pdf", "pdf content".getBytes());

        UploadDocumentResponse response = uploadService.upload(kbId, file);

        assertThat(response.status()).isEqualTo("PENDING");
        verify(kafkaTemplate).send(any(), any(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DocumentUploadServiceTest -q
# Expected: FAIL — classes not yet created
```

- [ ] **Step 3: Create `StorageService` interface and implementation**

```java
// src/main/java/com/majm/rag/ingestion/StorageService.java
package com.majm.rag.ingestion;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface StorageService {
    String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId);
    void delete(String filePath);
}
```

```java
// src/main/java/com/majm/rag/ingestion/LocalStorageService.java
package com.majm.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    @Value("${app.storage.base-path:/data/uploads}")
    private String basePath;

    @Override
    public String store(MultipartFile file, UUID knowledgeBaseId, UUID documentId) {
        try {
            Path dir = Path.of(basePath, knowledgeBaseId.toString(), documentId.toString());
            Files.createDirectories(dir);
            Path dest = dir.resolve(file.getOriginalFilename());
            file.transferTo(dest);
            return dest.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
    }

    @Override
    public void delete(String filePath) {
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("Could not delete file: {}", filePath, e);
        }
    }
}
```

- [ ] **Step 4: Create `IngestionMessage.java` and `UploadDocumentResponse.java`**

```java
// src/main/java/com/majm/rag/ingestion/dto/IngestionMessage.java
package com.majm.rag.ingestion.dto;

import java.util.UUID;

public record IngestionMessage(UUID documentId, UUID knowledgeBaseId) {}
```

```java
// src/main/java/com/majm/rag/ingestion/dto/UploadDocumentResponse.java
package com.majm.rag.ingestion.dto;

import java.util.UUID;

public record UploadDocumentResponse(UUID documentId, String name, String status) {}
```

- [ ] **Step 5: Create `DocumentUploadService.java`**

```java
// src/main/java/com/majm/rag/ingestion/DocumentUploadService.java
package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.ingestion.topic:document.ingestion}")
    private String ingestionTopic;

    @Transactional
    public UploadDocumentResponse upload(UUID knowledgeBaseId, MultipartFile file) {
        KnowledgeBase kb = kbService.getById(knowledgeBaseId);

        Document doc = new Document();
        doc.setKnowledgeBase(kb);
        doc.setName(file.getOriginalFilename());
        doc.setFileType(detectFileType(file.getOriginalFilename()));
        Document savedDoc = documentRepository.save(doc);

        String path = storageService.store(file, knowledgeBaseId, savedDoc.getId());
        savedDoc.setFilePath(path);
        documentRepository.save(savedDoc);

        kafkaTemplate.send(ingestionTopic, savedDoc.getId().toString(),
            new IngestionMessage(savedDoc.getId(), knowledgeBaseId));

        return new UploadDocumentResponse(savedDoc.getId(), savedDoc.getName(),
            savedDoc.getStatus().name());
    }

    private String detectFileType(String filename) {
        if (filename == null) return "TXT";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "DOCX";
        if (lower.endsWith(".md")) return "MD";
        if (lower.startsWith("http://") || lower.startsWith("https://")) return "URL";
        return "TXT";
    }
}
```

- [ ] **Step 6: Create `DocumentController.java`**

```java
// src/main/java/com/majm/rag/knowledge/DocumentController.java
package com.majm.rag.knowledge;

import com.majm.rag.ingestion.DocumentUploadService;
import com.majm.rag.ingestion.dto.UploadDocumentResponse;
import com.majm.rag.knowledge.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    @PostMapping
    public ResponseEntity<UploadDocumentResponse> upload(@PathVariable UUID kbId,
                                                          @RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(uploadService.upload(kbId, file));
    }

    @GetMapping
    public Page<DocumentListItem> list(@PathVariable UUID kbId, Pageable pageable) {
        return documentRepository.findByKnowledgeBaseId(kbId, pageable)
            .map(d -> new DocumentListItem(d.getId(), d.getName(), d.getFileType(),
                d.getStatus().name(), d.getChunkCount(), d.getCreatedAt()));
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable UUID kbId, @PathVariable UUID docId) {
        chunkRepository.deleteByDocumentId(docId);
        documentRepository.deleteById(docId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{docId}/chunks")
    public List<DocumentChunk> chunks(@PathVariable UUID kbId, @PathVariable UUID docId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
    }

    record DocumentListItem(UUID id, String name, String fileType, String status,
                             int chunkCount, java.time.LocalDateTime createdAt) {}
}
```

- [ ] **Step 7: Run test to verify it passes**

```bash
mvn test -Dtest=DocumentUploadServiceTest -q
# Expected: Tests run: 1, Failures: 0, Errors: 0
```

- [ ] **Step 8: Commit**

```bash
git add src/
git commit -m "feat: document upload service with Kafka publish and storage abstraction"
```

---

## Task 6: Kafka Config + Ingestion Consumer

**Files:**
- Create: `src/main/java/com/majm/rag/config/KafkaConfig.java`
- Create: `src/main/java/com/majm/rag/ingestion/DocumentParserFactory.java`
- Create: `src/main/java/com/majm/rag/ingestion/IngestionConsumer.java`
- Create: `src/test/java/com/majm/rag/ingestion/DocumentParserFactoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/majm/rag/ingestion/DocumentParserFactoryTest.java
package com.majm.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserFactoryTest {

    private final DocumentParserFactory factory = new DocumentParserFactory();

    @Test
    void create_pdfType_returnsTikaReader() {
        DocumentReader reader = factory.create("PDF", "/some/file.pdf");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_docxType_returnsTikaReader() {
        DocumentReader reader = factory.create("DOCX", "/some/file.docx");
        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void create_unknownType_throwsException() {
        assertThatThrownBy(() -> factory.create("XLS", "/some/file.xls"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XLS");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DocumentParserFactoryTest -q
# Expected: FAIL — DocumentParserFactory not yet created
```

- [ ] **Step 3: Create `KafkaConfig.java`**

```java
// src/main/java/com/majm/rag/config/KafkaConfig.java
package com.majm.rag.config;

import com.majm.rag.ingestion.dto.IngestionMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
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
```

- [ ] **Step 4: Create `DocumentParserFactory.java`**

```java
// src/main/java/com/majm/rag/ingestion/DocumentParserFactory.java
package com.majm.rag.ingestion;

import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;

@Component
public class DocumentParserFactory {

    public DocumentReader create(String fileType, String filePath) {
        return switch (fileType.toUpperCase()) {
            case "PDF", "DOCX" -> new TikaDocumentReader(new FileSystemResource(filePath));
            case "MD", "TXT"   -> new TextReader(new FileSystemResource(filePath));
            case "URL"         -> {
                try {
                    yield new TikaDocumentReader(new UrlResource(filePath));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("Invalid URL: " + filePath, e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported file type: " + fileType);
        };
    }
}
```

- [ ] **Step 5: Create `IngestionConsumer.java`**

```java
// src/main/java/com/majm/rag/ingestion/IngestionConsumer.java
package com.majm.rag.ingestion;

import com.majm.rag.ingestion.dto.IngestionMessage;
import com.majm.rag.knowledge.DocumentRepository;
import com.majm.rag.knowledge.KnowledgeBaseService;
import com.majm.rag.knowledge.domain.Document;
import com.majm.rag.knowledge.domain.DocumentStatus;
import com.majm.rag.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionConsumer {

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseService kbService;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    @Transactional
    public void consume(IngestionMessage message) {
        log.info("Ingesting document {}", message.documentId());

        Document doc = documentRepository.findById(message.documentId())
            .orElseThrow(() -> new IllegalStateException("Document not found: " + message.documentId()));
        KnowledgeBase kb = kbService.getById(message.knowledgeBaseId());

        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        try {
            DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
            List<org.springframework.ai.document.Document> rawDocs = reader.get();

            TokenTextSplitter splitter = new TokenTextSplitter(kb.getChunkSize(), kb.getChunkOverlap(),
                5, 10000, true);
            List<org.springframework.ai.document.Document> chunks = splitter.apply(rawDocs);

            chunks.forEach(chunk -> chunk.getMetadata().putAll(Map.of(
                "knowledge_base_id", kb.getId().toString(),
                "document_id", doc.getId().toString(),
                "document_name", doc.getName()
            )));

            vectorStore.add(chunks);

            doc.setStatus(DocumentStatus.DONE);
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);
            log.info("Ingestion complete for document {}: {} chunks", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for document {}", doc.getId(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            throw e;
        }
    }
}
```

- [ ] **Step 6: Run parser factory test to verify it passes**

```bash
mvn test -Dtest=DocumentParserFactoryTest -q
# Expected: Tests run: 3, Failures: 0, Errors: 0
```

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat: Kafka config, document parser factory, and ingestion consumer"
```

---

## Task 7: Vector Store Config + Model Router

**Files:**
- Create: `src/main/java/com/majm/rag/config/VectorStoreConfig.java`
- Create: `src/main/java/com/majm/rag/config/LlmConfig.java`
- Create: `src/main/java/com/majm/rag/retrieval/ModelRouter.java`
- Create: `src/main/java/com/majm/rag/retrieval/RetrievalService.java`

- [ ] **Step 1: Create `VectorStoreConfig.java`**

```java
// src/main/java/com/majm/rag/config/VectorStoreConfig.java
package com.majm.rag.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .indexType(HNSW)
            .distanceType(COSINE_DISTANCE)
            .dimensions(1536)
            .initializeSchema(false)
            .tableName("document_chunk")
            .build();
    }
}
```

- [ ] **Step 2: Create `LlmConfig.java`**

```java
// src/main/java/com/majm/rag/config/LlmConfig.java
package com.majm.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmConfig {

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
```

- [ ] **Step 3: Create `ModelRouter.java`**

```java
// src/main/java/com/majm/rag/retrieval/ModelRouter.java
package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final Map<String, EmbeddingModel> embeddingModels;

    public EmbeddingModel getEmbeddingModel(String modelName) {
        return embeddingModels.values().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No embedding model available for: " + modelName));
    }
}
```

- [ ] **Step 4: Create `RetrievalService.java`**

```java
// src/main/java/com/majm/rag/retrieval/RetrievalService.java
package com.majm.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final VectorStore vectorStore;

    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq("knowledge_base_id", knowledgeBaseId.toString()).build())
                .build()
        );
    }
}
```

- [ ] **Step 5: Add retrieval test endpoint to `KnowledgeBaseController.java`**

Add the following method to `KnowledgeBaseController.java`:

```java
// Add to KnowledgeBaseController — also inject RetrievalService in constructor
@PostMapping("/{id}/search")
public List<SearchResultItem> search(@PathVariable UUID id,
                                      @RequestBody SearchRequest request) {
    return retrievalService.search(id, request.query(), request.topK())
        .stream()
        .map(doc -> new SearchResultItem(doc.getText(), doc.getMetadata()))
        .toList();
}

record SearchRequest(String query, int topK) {}
record SearchResultItem(String content, java.util.Map<String, Object> metadata) {}
```

Also update the `KnowledgeBaseController` constructor to inject `RetrievalService`:

```java
private final KnowledgeBaseService service;
private final RetrievalService retrievalService;
```

- [ ] **Step 6: Verify compile**

```bash
mvn compile
# Expected: BUILD SUCCESS
```

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat: vector store config, model router, retrieval service, and search endpoint"
```

---

## Task 8: Chat Service + Controller (SSE Streaming)

**Files:**
- Create: `src/main/java/com/majm/rag/chat/dto/ChatRequest.java`
- Create: `src/main/java/com/majm/rag/chat/dto/CreateConversationRequest.java`
- Create: `src/main/java/com/majm/rag/chat/dto/ConversationMessageRequest.java`
- Create: `src/main/java/com/majm/rag/chat/ChatService.java`
- Create: `src/main/java/com/majm/rag/chat/ChatController.java`
- Create: `src/test/java/com/majm/rag/chat/ChatServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/majm/rag/chat/ChatServiceTest.java
package com.majm.rag.chat;

import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private RetrievalService retrievalService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatClient chatClient;

    @InjectMocks
    private ChatService chatService;

    @Test
    void buildContext_shouldConcatenateChunkContents() {
        UUID kbId = UUID.randomUUID();
        Document doc1 = new Document("chunk one", Map.of());
        Document doc2 = new Document("chunk two", Map.of());

        when(retrievalService.search(any(), any(), anyInt()))
            .thenReturn(List.of(doc1, doc2));

        String context = chatService.buildContext(kbId, "test query", 5);

        assertThat(context).contains("chunk one");
        assertThat(context).contains("chunk two");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=ChatServiceTest -q
# Expected: FAIL — ChatService not yet created
```

- [ ] **Step 3: Create DTOs**

```java
// src/main/java/com/majm/rag/chat/dto/ChatRequest.java
package com.majm.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChatRequest(
    @NotNull UUID knowledgeBaseId,
    @NotBlank String question,
    int topK
) {
    public ChatRequest {
        if (topK <= 0) topK = 5;
    }
}
```

```java
// src/main/java/com/majm/rag/chat/dto/CreateConversationRequest.java
package com.majm.rag.chat.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateConversationRequest(@NotNull UUID knowledgeBaseId) {}
```

```java
// src/main/java/com/majm/rag/chat/dto/ConversationMessageRequest.java
package com.majm.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record ConversationMessageRequest(@NotBlank String question, int topK) {
    public ConversationMessageRequest {
        if (topK <= 0) topK = 5;
    }
}
```

- [ ] **Step 4: Create `ChatService.java`**

```java
// src/main/java/com/majm/rag/chat/ChatService.java
package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.CreateConversationRequest;
import com.majm.rag.retrieval.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String RAG_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer questions based only on the provided context.
        If the context does not contain enough information, say so clearly.
        
        Context:
        {context}
        """;

    private final RetrievalService retrievalService;
    private final ConversationRepository conversationRepository;
    private final ChatClient chatClient;

    public String buildContext(UUID knowledgeBaseId, String query, int topK) {
        List<Document> chunks = retrievalService.search(knowledgeBaseId, query, topK);
        return chunks.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    public Flux<String> chat(ChatRequest request) {
        String context = buildContext(request.knowledgeBaseId(), request.question(), request.topK());
        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .user(request.question())
            .stream()
            .content();
    }

    @Transactional
    public Conversation createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setKnowledgeBaseId(request.knowledgeBaseId());
        return conversationRepository.save(conv);
    }

    @Transactional
    public Flux<String> continueConversation(UUID conversationId, ConversationMessageRequest request) {
        Conversation conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));

        String context = buildContext(conv.getKnowledgeBaseId(), request.question(), request.topK());

        List<Map<String, String>> history = conv.getMessages();
        history.add(Map.of("role", "user", "content", request.question()));
        conv.setMessages(history);
        conversationRepository.save(conv);

        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .messages(history.stream()
                .map(m -> m.get("role").equals("user")
                    ? new org.springframework.ai.chat.messages.UserMessage(m.get("content"))
                    : new org.springframework.ai.chat.messages.AssistantMessage(m.get("content")))
                .collect(Collectors.toList()))
            .stream()
            .content()
            .doOnComplete(() -> {
                // Save assistant message after streaming completes
            });
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=ChatServiceTest -q
# Expected: Tests run: 1, Failures: 0, Errors: 0
```

- [ ] **Step 6: Create `ChatController.java`**

```java
// src/main/java/com/majm/rag/chat/ChatController.java
package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.CreateConversationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(
            @Valid @RequestBody CreateConversationRequest request) {
        return ResponseEntity.ok(chatService.createConversation(request));
    }

    @PostMapping(value = "/conversations/{id}/messages",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> continueConversation(@PathVariable UUID id,
            @Valid @RequestBody ConversationMessageRequest request) {
        return chatService.continueConversation(id, request);
    }
}
```

- [ ] **Step 7: Run all tests**

```bash
mvn test
# Expected: all tests pass, no failures
```

- [ ] **Step 8: Commit**

```bash
git add src/
git commit -m "feat: RAG chat service and SSE streaming controller with multi-turn conversation"
```

---

## Task 9: End-to-End Smoke Test

- [ ] **Step 1: Start all services**

```bash
docker compose up -d
mvn spring-boot:run &
sleep 8
```

- [ ] **Step 2: Create a knowledge base**

```bash
KB_ID=$(curl -s -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -d '{"name":"Smoke Test KB","description":"e2e test","embeddingModel":"text-embedding-3-small","chunkSize":256,"chunkOverlap":32}' \
  | jq -r .id)
echo "KB_ID=$KB_ID"
# Expected: UUID printed
```

- [ ] **Step 3: Upload a text document**

```bash
echo "Spring AI is a framework for building AI applications using Spring Boot." > /tmp/test.txt

curl -s -X POST "http://localhost:8080/api/v1/knowledge-bases/$KB_ID/documents" \
  -F "file=@/tmp/test.txt" | jq .
# Expected: {"documentId":"...","name":"test.txt","status":"PENDING"}
```

- [ ] **Step 4: Poll document status until DONE**

```bash
DOC_ID=<document-id-from-above>
sleep 5
curl -s "http://localhost:8080/api/v1/knowledge-bases/$KB_ID/documents" | jq '.content[0].status'
# Expected: "DONE"
```

- [ ] **Step 5: Test vector search**

```bash
curl -s -X POST "http://localhost:8080/api/v1/knowledge-bases/$KB_ID/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"What is Spring AI?","topK":3}' | jq .
# Expected: array with content containing "Spring AI"
```

- [ ] **Step 6: Test chat (requires valid OPENAI_API_KEY or Ollama running)**

```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d "{\"knowledgeBaseId\":\"$KB_ID\",\"question\":\"What is Spring AI?\",\"topK\":3}"
# Expected: SSE stream with answer grounded in the uploaded document
```

- [ ] **Step 7: Final commit**

```bash
git add .
git commit -m "docs: end-to-end smoke test verified"
```

---

## Dev Log

Append entries here as development progresses.

| Date | Task | Notes |
|------|------|-------|
| 2026-04-29 | Design | Spec approved. Kafka for async ingestion, PG18+pgvector, Spring AI 2.0 |
