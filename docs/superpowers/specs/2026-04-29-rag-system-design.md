# RAG System Design

**Date:** 2026-04-29  
**Stack:** Spring Boot 3.x + Spring AI 1.x + PostgreSQL 18/pgvector + Kafka

---

## 1. Goals

Build a RAG (Retrieval-Augmented Generation) system for enterprise internal knowledge base and personal knowledge management scenarios. Users upload documents in various formats; the system parses, chunks, and vectorizes them asynchronously. A chat API allows querying the knowledge base with LLM-generated answers grounded in retrieved document chunks.

**In scope:**
- Knowledge base CRUD with per-KB chunking strategy configuration
- Document management: upload, status tracking, chunk inspection, deletion
- Multi-format document ingestion: PDF, DOCX, Markdown, TXT, URL
- Async ingestion pipeline via Kafka
- RAG chat API with SSE streaming (single-turn and multi-turn)
- Retrieval test endpoint for debugging
- Multi-LLM support (OpenAI, Qwen, DeepSeek, Ollama) via configuration

**Out of scope (this iteration):**
- Frontend UI (deferred)
- Multi-tenancy / permission management
- Object storage integration (local filesystem only, behind abstraction)

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Spring Boot Application                 │
│                                                         │
│  ┌──────────────┐   ┌──────────────┐  ┌─────────────┐  │
│  │  Knowledge   │   │  Ingestion   │  │    Chat     │  │
│  │   Module     │   │   Module     │  │   Module    │  │
│  │              │   │              │  │             │  │
│  │ - KB CRUD    │   │ - Doc parse  │  │ - RAG query │  │
│  │ - Doc upload │──▶│ - Chunking   │  │ - History   │  │
│  │ - Doc mgmt   │   │ - Embedding  │  │ - Streaming │  │
│  └──────────────┘   └──────┬───────┘  └──────┬──────┘  │
│                             │                 │         │
└─────────────────────────────│─────────────────│─────────┘
                              │                 │
              ┌───────────────▼──┐    ┌─────────▼────────┐
              │     Kafka        │    │   LLM Provider   │
              │ document.        │    │ OpenAI / Qwen /  │
              │ ingestion topic  │    │ DeepSeek / Ollama│
              └───────┬──────────┘    └──────────────────┘
                      │
         ┌────────────▼─────────────┐
         │   PostgreSQL + pgvector  │
         │  - knowledge_base        │
         │  - document              │
         │  - document_chunk        │
         │  - conversation          │
         └──────────────────────────┘
```

**Upload flow:** HTTP upload → 202 Accepted → Kafka message → async Consumer → parse/chunk/embed → pgvector → status updated to DONE

**Query flow:** User question → embed query → pgvector similarity search (filtered by `knowledge_base_id`) → inject Top-K chunks into prompt → LLM → SSE stream response

---

## 3. Data Model

```sql
-- Knowledge base
knowledge_base (
  id             UUID PRIMARY KEY,
  name           VARCHAR NOT NULL,
  description    TEXT,
  embedding_model VARCHAR NOT NULL,  -- e.g. "text-embedding-3-small"
  chunk_size     INT DEFAULT 512,
  chunk_overlap  INT DEFAULT 64,
  status         VARCHAR DEFAULT 'ACTIVE',  -- ACTIVE | ARCHIVED
  created_at     TIMESTAMP,
  updated_at     TIMESTAMP
)

-- Document
document (
  id                 UUID PRIMARY KEY,
  knowledge_base_id  UUID REFERENCES knowledge_base(id),
  name               VARCHAR NOT NULL,
  file_type          VARCHAR NOT NULL,  -- PDF | DOCX | MD | TXT | URL
  file_path          VARCHAR,           -- local path or storage key
  status             VARCHAR DEFAULT 'PENDING',  -- PENDING | PROCESSING | DONE | FAILED
  error_message      TEXT,
  chunk_count        INT DEFAULT 0,
  created_at         TIMESTAMP,
  updated_at         TIMESTAMP
)

-- Document chunk (vector store)
document_chunk (
  id                 UUID PRIMARY KEY,
  document_id        UUID REFERENCES document(id),
  knowledge_base_id  UUID NOT NULL,  -- denormalized for fast filter
  content            TEXT NOT NULL,
  metadata           JSONB,          -- page number, title, source URL, etc.
  embedding          VECTOR(1536),   -- pgvector column
  chunk_index        INT,
  created_at         TIMESTAMP
)

-- Conversation (multi-turn chat history)
conversation (
  id                 UUID PRIMARY KEY,
  knowledge_base_id  UUID REFERENCES knowledge_base(id),
  messages           JSONB NOT NULL DEFAULT '[]',
  created_at         TIMESTAMP,
  updated_at         TIMESTAMP
)
```

**Indexes:**
- `document_chunk.embedding`: `hnsw` index for approximate nearest-neighbor search
- `document_chunk.knowledge_base_id`: btree index for fast filtering

Schema managed via **Flyway** migrations.

---

## 4. API

### Knowledge Base
```
POST   /api/v1/knowledge-bases               Create knowledge base
GET    /api/v1/knowledge-bases               List (paginated)
GET    /api/v1/knowledge-bases/{id}          Get detail
PUT    /api/v1/knowledge-bases/{id}          Update config
DELETE /api/v1/knowledge-bases/{id}          Delete (cascades documents + vectors)
```

### Document Management
```
POST   /api/v1/knowledge-bases/{id}/documents              Upload document → 202 Accepted
GET    /api/v1/knowledge-bases/{id}/documents              List documents (paginated)
DELETE /api/v1/knowledge-bases/{id}/documents/{docId}      Delete document + its chunks
GET    /api/v1/knowledge-bases/{id}/documents/{docId}/chunks  Inspect chunks
```

### Retrieval Test
```
POST   /api/v1/knowledge-bases/{id}/search   Test vector search, returns Top-K chunks
```

### Chat
```
POST   /api/v1/chat                                        Single-turn RAG (SSE stream)
POST   /api/v1/chat/conversations                          Create conversation
POST   /api/v1/chat/conversations/{id}/messages            Multi-turn message (SSE stream)
```

---

## 5. Ingestion Pipeline

```
Upload request
    │
    ▼
DocumentController
    │  1. Save file to local storage (/data/uploads/{kb_id}/{doc_id}/)
    │  2. Create document record (status=PENDING)
    │  3. Publish to Kafka topic: document.ingestion
    └─ Return 202 Accepted

Kafka Topic: document.ingestion
    │
    ▼
IngestionConsumer (@KafkaListener)
    │  1. Update status=PROCESSING
    │  2. DocumentReader (by file_type):
    │     - PDF / DOCX  → TikaDocumentReader
    │     - MD / TXT    → TextReader
    │     - URL         → WebPageDocumentReader
    │  3. TokenTextSplitter
    │     (chunk_size / chunk_overlap from knowledge_base config)
    │  4. EmbeddingModel.embed(chunks)
    │     (provider selected by knowledge_base.embedding_model)
    │  5. PgVectorStore.add(chunks)
    │     (metadata includes knowledge_base_id, document_id, chunk_index)
    │  6. Update status=DONE, chunk_count=N
    │
    └─ On failure: status=FAILED, error_message stored
                   retry up to 3x with exponential backoff
                   → dead letter topic: document.ingestion.dlt
```

**Error handling:** `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries(3)`. DLT messages can be requeued via a manual admin endpoint.

**Storage abstraction:** `StorageService` interface with `LocalStorageService` implementation. Replace with `OssStorageService` without changing callers.

---

## 6. Multi-LLM Configuration

Spring AI supports multiple providers via `application.yml` profiles or runtime bean selection:

```yaml
# Default provider selected via spring.profiles.active
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat.options.model: gpt-4o-mini
    ollama:
      base-url: http://localhost:11434
      chat.options.model: llama3.2
```

Chat and embedding beans resolved by qualifier at runtime. Knowledge base stores `embedding_model` name; a `ModelRouter` component maps it to the correct `EmbeddingModel` bean.

---

## 7. Technology Stack

| Layer | Choice |
|-------|--------|
| Framework | Spring Boot 3.x + Spring AI 1.x |
| Database | PostgreSQL 18 + pgvector |
| ORM / Migration | Spring Data JPA + Flyway |
| Message Queue | Kafka |
| Document Parsing | Apache Tika, Spring AI Readers |
| Vector Store | Spring AI PgVectorStore |
| LLM Providers | OpenAI, Qwen, DeepSeek, Ollama (via Spring AI) |
| File Storage | Local filesystem (`StorageService` abstraction) |
| API Style | REST + SSE (chat endpoints) |
| Build Tool | Maven |

**Key Spring AI starters:**
```xml
spring-ai-openai-spring-boot-starter
spring-ai-pgvector-store-spring-boot-starter
spring-ai-tika-document-reader
spring-ai-pdf-document-reader
spring-ai-ollama-spring-boot-starter
```

---

## 8. Project Structure

```
src/main/java/com/majm/rag/
├── knowledge/
│   ├── KnowledgeBaseController.java
│   ├── KnowledgeBaseService.java
│   ├── KnowledgeBaseRepository.java
│   └── domain/  (KnowledgeBase.java, Document.java, DocumentChunk.java)
├── ingestion/
│   ├── DocumentUploadService.java   # handles upload + Kafka publish
│   ├── IngestionConsumer.java       # @KafkaListener
│   ├── DocumentParserFactory.java   # selects reader by file_type
│   └── StorageService.java          # interface + LocalStorageService
├── retrieval/
│   ├── RetrievalService.java        # vector search with KB filter
│   └── ModelRouter.java             # maps embedding_model name → bean
├── chat/
│   ├── ChatController.java
│   ├── ChatService.java             # RAG prompt assembly + LLM call
│   └── ConversationRepository.java
└── config/
    ├── KafkaConfig.java
    ├── VectorStoreConfig.java
    └── LlmConfig.java
```
