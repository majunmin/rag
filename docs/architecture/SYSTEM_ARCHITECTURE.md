# 系统架构方案

**项目**: RAG 知识库系统（rag0429 后端 + rag-admin 前端）
**版本**: 0.0.1-SNAPSHOT，预上线状态
**最后更新**: 2026-07-21

---

## 1. 系统定位

为内部团队提供基于检索增强生成（RAG, Retrieval-Augmented Generation）的知识库问答能力。用户可上传文档（PDF / DOCX / TXT / MD）或提交公开网页 URL，系统提取正文并切分为 chunk，调用 embedding 模型向量化后存入 pgvector；提问时以语义相似度召回相关 chunk，连同问题一起送入 LLM 生成答案，支持多轮对话。

**典型场景**：内部产品手册问答、API 文档检索、运维 runbook 助手。

**非目标**：对外服务、多租户隔离、用户管理与计费。

---

## 2. 总体架构

### 2.1 拓扑

```
┌────────────────────────────────────────────────────────────────────┐
│                          浏览器 (内网)                              │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  rag-admin  (React 19 + Vite + AntD 6 + TanStack Query)        │ │
│  │  /knowledge-bases   /chat   /...documents   /search            │ │
│  └───────────────────────────────────────────────────────────────┘ │
└──────────────┬─────────────────────────────────────────────────────┘
               │ HTTPS（生产经 Nginx 反代；dev 经 Vite proxy）
               │ Header: X-API-Key（可选开启）
               ▼
┌────────────────────────────────────────────────────────────────────┐
│                  rag0429 后端 (Spring Boot 3.3.5)                  │
│                                                                    │
│   /api/v1/knowledge-bases          KnowledgeBaseController         │
│   /api/v1/.../documents            DocumentController              │
│   /api/v1/chat   /chat/conversations   ChatController (SSE)        │
│   /api/actuator/health             Spring Boot Actuator            │
│                                                                    │
│   ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐           │
│   │   Web 层    │→ │   Service 层 │→ │   Persistence    │           │
│   │ (Controller)│  │ (KbService、 │  │   (Spring Data   │           │
│   │             │  │  DocService、│  │    JPA + JDBC)   │           │
│   │             │  │  ChatService)│  │                  │           │
│   └─────────────┘  └──────┬───────┘  └────────┬────────┘           │
│                           │                   │                    │
│                           ▼                   │                    │
│                    ┌──────────────┐           │                    │
│                    │ Spring AI    │           │                    │
│                    │ ChatClient + │           │                    │
│                    │ VectorStore  │           │                    │
│                    └──┬─────┬─────┘           │                    │
│                       │     │                 │                    │
│   ┌───────────────────┼─────┼─────────────────┼─────┐              │
│   │   Async ingestion │     │ embedding API   │     │              │
│   │   (Kafka)         │     │                 │     │              │
│   │ ┌──────────────┐  │     │                 │     │              │
│   │ │  Ingestion   │  │     │                 │     │              │
│   │ │   Outbox     │  │     │                 │     │              │
│   │ │  Publisher   │  │     │                 │     │              │
│   │ └──────┬───────┘  │     │                 │     │              │
│   │        ▼          │     │                 │     │              │
│   │   Kafka topic     │     │                 │     │              │
│   │ document.ingestion│     │                 │     │              │
│   │        │          │     │                 │     │              │
│   │        ▼          │     │                 │     │              │
│   │ ┌──────────────┐  │     │                 │     │              │
│   │ │  Ingestion   │  │     │                 │     │              │
│   │ │  Consumer    │──┘     │                 │     │              │
│   │ │ + Status     │        │                 │     │              │
│   │ │   Service    │        │                 │     │              │
│   │ │ + Parser     │        │                 │     │              │
│   │ │   Factory    │        │                 │     │              │
│   │ └──────┬───────┘        │                 │     │              │
│   │        │ on failure     │                 │     │              │
│   │        ▼                │                 │     │              │
│   │   Kafka DLT             │                 │     │              │
│   │ document.ingestion.dlt  │                 │     │              │
│   └───────────────────┬─────┴─────────────────┘     │              │
└───────────────────────┼───────────────────────────────────┬────────┘
                        │                                   │
                        ▼                                   ▼
            ┌─────────────────────────┐         ┌──────────────────────┐
            │   PostgreSQL 18 +       │         │   阿里云百炼 DashScope │
            │   pgvector              │         │   (OpenAI 兼容 API)   │
            │                         │         │                      │
            │  knowledge_base         │         │   /embeddings         │
            │  document               │         │   /chat/completions   │
            │  conversation           │         │                      │
            │  ingestion_outbox       │         │                      │
            │  vector_store (HNSW)    │         │   max batch = 10     │
            └─────────────────────────┘         └──────────────────────┘
                        │
                        ▼
            ┌─────────────────────────┐
            │   本地磁盘存储            │
            │   ~/rag-uploads/         │
            │     <kbId>/              │
            │       <docId>.<ext>      │
            └─────────────────────────┘
```

### 2.2 进程与端口

| 进程 | 端口 | 部署方式 |
|---|---|---|
| rag0429 (Spring Boot) | 8080 | jar 直跑 / Docker |
| rag-admin (Nginx 静态) | 生产 80/443，dev 5173 | 静态资源 + Nginx 反代 |
| PostgreSQL 18 + pgvector | 5432 | docker-compose |
| Kafka 3.8（KRaft，单节点） | 9092 | docker-compose |

### 2.3 仓库划分

两个独立 git 仓库（前后端独立维护）：

- `rag0429/` —— 后端 Spring Boot 服务
- `rag-admin/` —— 前端 React 单页应用

前端通过 `/api/*` 路径调用后端，开发期 Vite proxy 转发到 `localhost:8080`，生产期由 Nginx 反代。

---

## 3. 技术栈

### 3.1 后端（rag0429）

| 类别 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.3.5 |
| AI 框架 | Spring AI | 1.1.5 |
| Web | Spring MVC（同步）+ Reactor Flux（SSE 流式） | 6.1.x / 3.6.x |
| 数据访问 | Spring Data JPA / Hibernate 6 + JdbcTemplate | 6.5.3 |
| 数据库迁移 | Flyway | 10.10.0 |
| 消息中间件 | Apache Kafka + Spring Kafka | 3.7 / 3.2.4 |
| 文档解析 | Spring AI PDF / Markdown / Jsoup Reader + Apache Tika | 1.1.5 / 3.3.0 |
| 文本切分 | 自定义 OverlappingTokenTextSplitter（cl100k_base） | — |
| 可观测性 | Spring Boot Actuator + Micrometer | — |
| 构建 | Maven | — |

### 3.2 前端（rag-admin）

| 类别 | 技术 |
|---|---|
| 框架 | React 19 + Vite 8 + TypeScript 6 |
| UI | Ant Design 6 |
| 路由 | React Router v7 |
| 服务端状态 | TanStack Query v5 |
| HTTP / SSE | 原生 `fetch` + `ReadableStream` |
| 测试 | Vitest + Testing Library |
| 包管理 | pnpm |

### 3.3 外部依赖

| 服务 | 角色 | 来源 |
|---|---|---|
| 阿里云 DashScope（百炼） | LLM (qwen-plus) + Embedding (text-embedding-v3, 1024 维) | OpenAI 兼容协议 |
| PostgreSQL 18 | 关系数据 + pgvector 向量存储 | docker image `pgvector/pgvector:pg18` |
| Apache Kafka | 异步摄入消息队列 | docker image `apache/kafka:3.8.0` |

---

## 4. 模块边界

### 4.1 后端模块

后端采用 Maven 多模块的模块化单体。各业务模块可独立编译和测试，最终由
`rag-app` 组装为一个 Spring Boot 进程：

```
rag-parent
├── rag-common        # 统一错误响应与跨模块异常
├── rag-knowledge     # 知识库、文档领域及持久化
├── rag-ingestion     # 文件/网页摄取、解析、Outbox、Kafka
├── rag-retrieval     # 向量召回、查询改写、重排与搜索 API
├── rag-chat          # 对话、SSE 与会话持久化
└── rag-app           # 启动类、共享配置、Flyway、集成测试
```

依赖关系保持单向：

```text
rag-app ─┬─> rag-chat ─> rag-retrieval
         ├─> rag-ingestion ─> rag-knowledge ─> rag-common
         ├─> rag-retrieval
         └─> rag-knowledge
```

业务模块内部使用统一包约定：

- `api`：Controller 和对外 DTO。
- `application`：用例编排和事务边界；`application.port` 定义跨层端口。
- `domain`：实体、值对象和领域状态。
- `infrastructure`：JPA/JDBC、Kafka、存储、文档解析等技术实现。
- `config`：只放模块私有配置；跨模块 Bean 在 `rag-app/config` 组装。

`StorageService` 端口由 `rag-knowledge` 定义、`rag-ingestion` 的
`LocalStorageService` 实现，因此删除知识库/文档无需反向依赖摄取模块。
搜索端点归 `rag-retrieval`，文档端点归 `rag-ingestion`，避免 Controller
跨域反向引用。

### 4.2 前端模块

```
rag-admin/src/
├── api/                  # 后端 fetch 封装
│   ├── client.ts         # request<T> + streamRequest（SSE 行缓冲）
│   ├── knowledge-base.ts
│   ├── document.ts
│   └── chat.ts
├── hooks/
│   ├── useKbList.ts      # TanStack Query
│   ├── useDocList.ts     # 含 PENDING/PROCESSING 时的 3s 自动轮询
│   └── useStreamingChat.ts
├── layouts/AppLayout.tsx # 侧边栏 + 顶栏 + /api/actuator/health 30s 轮询
├── pages/
│   ├── knowledge-base/   # 列表 + 创建/编辑 Modal
│   ├── document/         # 文件上传/网页 URL + 列表 + ChunkDrawer + ErrorModal
│   └── chat/             # KB 选择 + 消息流 + topK 调节 + 多/单轮切换 + ChunkContextDrawer
├── types/api.ts          # 后端 DTO 镜像类型
└── test/                 # Vitest（client.ts 单测，覆盖 SSE 跨 chunk 缓冲）
```

---

## 5. 关键流程

### 5.1 文档摄入（异步）

```
[用户]
  ├─ 文件: POST /api/v1/knowledge-bases/{kbId}/documents (multipart)
  └─ 网页: POST /api/v1/knowledge-bases/{kbId}/documents/url ({url})
  ▼
[DocumentController]
  ├─ 文件: DocumentUploadService.upload (in @Transactional)
  │   ├─ 校验扩展名（pdf/docx/doc/md/txt 白名单 + 25MB 上限）
  │   └─ LocalStorageService.store → ~/rag-uploads/<kbId>/<docId>.<ext>  ← 路径用 docId，避免 traversal
  ├─ 网页: WebDocumentService.submit
  │   ├─ WebUrlPolicy 校验公网 http/https 地址（DNS、凭据、片段）
  │   └─ DocumentUploadService.submitUrl (in @Transactional，不落本地文件)
  ├─ documentRepository.saveAndFlush(doc, status=PENDING)
  └─ outboxRepository.enqueue(docId, kbId)
  ▼
[Tx commit: document + ingestion_outbox 原子提交]
  │ ─────── (response 202 Accepted to user) ───────
  ▼
[IngestionOutboxPublisher @Scheduled]
  ├─ SELECT ... FOR UPDATE SKIP LOCKED LIMIT batchSize
  ├─ kafkaTemplate.send(...).get(sendTimeout)
  ├─ success: status=PUBLISHED
  └─ failure: attempt_count+1, next_attempt_at 指数退避, last_error
  ▼
[IngestionConsumer.consume @KafkaListener]
  ├─ statusService.markProcessing(docId)         (短事务 1；行锁；DONE/缺失则跳过)
  ├─ IngestionProcessor.process(msg)              (事务 2；持有文档行锁)
  │   ├─ parserFactory.create(fileType, filePath).get()
  │   │   ├─ PDF: PagePdfDocumentReader（按页提取并保留页码 metadata）
  │   │   ├─ Markdown: MarkdownDocumentReader（保留代码块与引用）
  │   │   ├─ DOCX / TXT: TikaDocumentReader / TextReader
  │   │   └─ URL: WebPageCrawler（安全下载后交给 JsoupDocumentReader 提取正文）
  │   ├─ OverlappingTokenTextSplitter (chunkSize, chunkOverlap)
  │   ├─ 每个 chunk 注入 metadata，并按 docId:chunkIndex 生成确定性 UUID
  │   ├─ vectorStore.add(chunks)                  (稳定 ID upsert)
  │   │   └─ FixedSizeBatchingStrategy.batch(chunks, 10)
  │   │       └─ 每批 ≤10 → embeddingModel.embed → INSERT INTO vector_store
  │   ├─ deleteByDocumentFromIndex(docId, count)  (成功后裁剪旧尾部)
  │   └─ status = DONE                            (与向量变更原子提交)
  │
  └─ on error: 事务 2 整体回滚；statusService.markFailed + throw (短事务 3)

   [Kafka DefaultErrorHandler] 失败时按 ExponentialBackoff(1s, 2s, 4s) 重试 3 次
   3 次仍失败 → DeadLetterPublishingRecoverer 写入 document.ingestion.dlt
```

**关键设计点**：
- **事务 Outbox**：上传事务原子写 document 与待发布事件，避免 DB 已提交但 Kafka 消息丢失。
- **至少一次 + 幂等消费**：发布确认前崩溃可能重复发送；DONE 短路、稳定 chunk ID 与 upsert 后裁剪使重试结果收敛，失败不会先清空旧向量。
- **删除与崩溃安全**：V7 将 metadata 中的文档 ID 映射为生成列并建立 `ON DELETE CASCADE` 外键；处理事务持有文档行锁，删除要么先完成、要么在处理提交后级联清理。
- **并发串行化**：重复 delivery 在 `findByIdForUpdate` 上串行，后到者在拿锁后看到 `DONE`，不重复 embedding。
- **处理事务**：parser、embedding HTTP、向量写入和 `DONE` 共用一个事务，以连接与锁持有时间换取 P0 原子性；后续可用 generation/staging 设计缩短事务。
- **`IngestionStatusService` 独立 bean**：避免 self-invocation 绕过 `@Transactional` AOP 代理（这是上线前修复的一个 showstopper bug）。
- **FixedSizeBatchingStrategy**：百炼 embedding 单批最多 10 个，默认 `TokenCountBatchingStrategy` 会塞几十个，必败。
- **DLT**：3 次重试后投递至 dead-letter topic，避免无限重试阻塞分区。
- **网页抓取边界**：只抓取提交的单个页面，不递归跟踪链接；仅接受公网 HTML，重定向目标也必须通过相同 URL 策略。

### 5.2 多轮对话（SSE 流式）

```
[用户] POST /api/v1/chat/conversations  body={knowledgeBaseId}
  ▼
[ChatController.createConversation]
  ▼
ResponseEntity 201 Created + Location + ConversationResponse {id, ..., version=0}
  ▼
[用户] POST /api/v1/chat/conversations/{id}/messages  body={question, topK<=50}
  ▼
[ChatController.continueConversation] returns Flux<ServerSentEvent<String>>
  │
  ▼
[ChatService.continueConversation]
  ├─ conv = conversationRepository.findById(id) (else 404)
  ├─ retrieveContext(kbId, question, topK, history):
  │   └─ retrievalService.search → vectorStore.similaritySearch
  │       (filter: metadata.knowledge_base_id == kbId; topK clamped to [1,50])
  │   → 同一批 chunk 同时生成 prompt context 与 SearchResultItem[]
  │
  ├─ history = trimHistory(conv.messages + new userMsg, max=20)   ← 滑窗
  ├─ conv.setMessages(history); conversationRepository.save(conv)
  │   └─ Hibernate UPDATE WHERE version=?  (乐观锁)
  │     若版本冲突 → ObjectOptimisticLockingFailureException → 409 CONCURRENT_UPDATE
  │
  └─ chatClient.prompt()
       .system(RAG_SYSTEM_PROMPT, context)
       .messages(history → UserMessage/AssistantMessage)
       .stream().content()
       .doOnNext(token → assistantReply.append(token))
       .doOnComplete(() →
            persistenceService.appendAssistantMessage(id, assistantReply)
                                 (独立 @Transactional 写入))
  ▼
[ChatSseEvents]
  └─ context（首帧）→ token* → done | error
```

**关键设计点**：
- **滑动窗口**：`app.chat.max-history-messages=20`，避免历史无限增长（每轮重写整 JSONB 列）。
- **乐观锁**：`Conversation.version`（V5 migration 加列），并发写时只有一个成功，另一个收到 409。
- **持久化分离**：`ConversationPersistenceService` 是独立 `@Service`，被 `chatService` 通过依赖注入调用，AOP 代理生效。
- **精确检索上下文**：SSE 首帧直接携带本轮 prompt 使用的 chunk，前端不做第二次检索。
- **结构化流错误**：中途失败发送 `error` 帧，携带 `STREAM_ERROR` 和 traceId；服务端日志保留完整异常。

### 5.3 知识库语义检索（直接 API）

简化版的检索：
```
POST /api/v1/knowledge-bases/{kbId}/search  body={query, topK}
  ▼
RetrievalService.search → 返回 List<SearchResultItem>
```

该端点供独立检索调试和 API 调用。Chat 测试台直接消费 SSE 的 `context` 帧，
不会在流结束后再次调用该端点。

---

## 6. 数据模型

### 6.1 表结构（Flyway V1-V8）

```sql
knowledge_base
  id UUID PK, name, description, embedding_model, chunk_size, chunk_overlap,
  status, created_at, updated_at

document
  id UUID PK,
  knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  name, file_type, file_path, status, error_message, chunk_count,
  created_at, updated_at
  INDEX (knowledge_base_id)

vector_store              -- Spring AI 标准表（V4 引入，V7 补文档外键）
  id UUID PK, content TEXT, metadata JSONB, embedding VECTOR(1024),
  document_id UUID GENERATED ... REFERENCES document(id) ON DELETE CASCADE
  HNSW INDEX (embedding vector_cosine_ops)
  GIN-style: ((metadata->>'document_id')), ((metadata->>'knowledge_base_id'))

conversation
  id UUID PK,
  knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  messages JSONB DEFAULT '[]',
  version BIGINT NOT NULL DEFAULT 0,    -- V5 加，乐观锁
  created_at, updated_at

ingestion_outbox          -- V6 引入的事务 Outbox
  id UUID PK,
  document_id UUID UNIQUE REFERENCES document(id) ON DELETE CASCADE,
  knowledge_base_id UUID REFERENCES knowledge_base(id) ON DELETE CASCADE,
  status, attempt_count, next_attempt_at, published_at, last_error,
  created_at, updated_at
```

### 6.2 删除级联策略

| 触发 | DB 行为 | 应用补充 |
|---|---|---|
| 删除 KB | document、conversation、vector_store 自动级联删除 | 收集 file_path，应用层显式清理向量兼容旧数据，再删除 KB 与磁盘文件 |
| 删除 Document | vector_store 自动级联删除 | 应用层仍显式清理向量兼容旧数据，再删除文档与磁盘文件 |

V7 的生成列从 JSONB metadata 提取 `document_id`，因此 PgVectorStore 无需改变 INSERT
列清单即可获得数据库外键保护；应用层显式删除保留为兼容与快速清理路径。

### 6.3 元数据约定

`vector_store.metadata` 是 JSONB，约定字段：
- `knowledge_base_id` — UUID 字符串
- `document_id` — UUID 字符串
- `document_name` — 原文件展示名（不参与检索过滤）
- `chunk_index` — 整数，chunk 在文档中的顺序

`RetrievalService` 通过 `FilterExpressionBuilder.eq("knowledge_base_id", kbId)` 把 KB 隔离下推到 SQL `WHERE metadata->>...`，再 ANN 召回。

---

## 7. 部署与配置

### 7.1 启动依赖

```bash
# 后端
docker-compose up -d        # 起 PG + Kafka
export DASHSCOPE_API_KEY=sk-xxx
./mvnw -pl rag-app -am spring-boot:run  # Flyway 自动执行 V1-V8

# 前端
cd ../rag-admin
pnpm install
pnpm dev                    # http://localhost:5173
```

### 7.2 关键配置（`rag-app/src/main/resources/application.yml`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `spring.datasource.{url,username,password}` | dev `rag/rag` | prod 由 `DB_*` env 强制覆盖（StartupValidator） |
| `spring.ai.openai.{base-url, api-key}` | DashScope 百炼 | prod 拒绝 `dummy` |
| `spring.ai.openai.embedding.options.model` | `text-embedding-v3` | 1024 维（V3 schema 对齐） |
| `spring.ai.vectorstore.pgvector.dimensions` | 1024 | 改维度时需重建 vector_store |
| `app.embedding.batch-size` | **10** | 百炼硬限；OpenAI 可设 2048 |
| `app.chat.max-history-messages` | **20** | 多轮对话滑窗 |
| `app.ingestion.outbox.delay-ms` | **1000** | Outbox 定时扫描间隔 |
| `app.ingestion.outbox.batch-size` | **20** | 每轮锁定并发布的事件数，运行时限制为 1-100 |
| `app.ingestion.outbox.send-timeout-ms` | **10000** | 等待 Kafka 发送确认的超时时间 |
| `app.security.api-key-auth.{enabled, expected-key}` | dev `false` | prod 通过 env `API_KEY_AUTH_ENABLED=true` + `API_KEY=xxx` 开启 |
| `spring.servlet.multipart.{max-file-size, max-request-size}` | 25MB / 30MB | 上传上限 |
| `management.endpoints.web.base-path` | `/api/actuator` | 与业务 API 同前缀 |
| `management.endpoints.web.exposure.include` | `health` | 只暴露 health；env/beans 等屏蔽 |

### 7.3 部署拓扑（生产建议）

```
            Internet / 内网
                  │
                  ▼
              Nginx 反代
        ┌─────────┴─────────┐
        │                   │
     /api/* → Spring Boot  /* → 静态文件 (rag-admin/dist)
                  │
        ┌─────────┼──────────┐
        ▼         ▼          ▼
    PG VM    Kafka VM    LLM 调用
                          (出口 NAT)
```

- **静态前端**：`pnpm build` → `dist/` 由 Nginx 直接 serve
- **后端**：`mvn package -DskipTests` → fat jar，systemd 或 K8s Deployment
- **数据卷**：磁盘存储 `/data/uploads`（替换默认 `~/rag-uploads`）；PG 数据卷
- **健康探针**：K8s readinessProbe 指向 `/api/actuator/health/readiness`

---

## 8. 当前已知约束与改进项

| 项 | 现状 | 改进 |
|---|---|---|
| SSE 事件 | `context / token / done / error` 四类帧；error 帧体为 `ErrorResponse` JSON 含 traceId | 前端直接展示精确 context，并用 `StreamServerError` 消费错误 |
| ModelRouter | 已删（dead code） | `KnowledgeBase.embeddingModel` 字段语义降为"informational only"，全局只用一个 EmbeddingModel |
| 单实例 Kafka | docker-compose 单 broker，无副本 | 生产改 3 broker + replication factor ≥ 2 |
| API Key 鉴权 | 单租户共享 key，过滤器实现 | 真要多用户，换 OAuth2 / JWT |
| 文件存储 | 本地磁盘；KB / Document 删除时已联动 `storageService.delete(filePath)` | 生产换 S3-compatible（OSS / MinIO） |
| 检索后处理 | recall→cross-encoder rerank→`score-threshold` 过滤→MMR 去冗余 | rerank 策略调研见 `docs/research/2026-05-11-rerank-strategies.md` |
| 查询重写 | 三策略链：Conversational（多轮指代消解）→ HyDE（假设性文档）/ Multi-Query（多查询 + RRF 融合）；fail-soft 回落原 query | 默认 `conversational,hyde`；可切 `conversational,multi-query` 看效果 |
| 集成测试 | 用本地 docker-compose 栈而非 Testcontainers | 等 Docker Engine 29 + docker-java 兼容性问题修复后切回 |
| LLM 调用观测 | 仅 Spring AI 默认 metrics | 接 OpenTelemetry，导出到 Tempo/Jaeger |

---

## 9. 测试与质量

| 类别 | 数量 | 说明 |
|---|---|---|
| 单元测试 | 后端 / 前端 | 覆盖 Outbox 入队发布、摄入幂等、精确 context SSE、API Key 请求头及原有领域行为；数量以测试运行报告为准 |
| 集成测试 | 2 | IngestionConsumer end-to-end（PG + Kafka + WireMock 拦截 LLM） |
| 健康端点 | 1 | `/api/actuator/health{,/liveness,/readiness}` |
| 已知遗漏 | — | ApiKeyFilter on/off 行为、ChatController 端到端 SSE |

发布前执行后端 `./mvnw test`，前端执行 `pnpm test -- --run && pnpm lint && pnpm build`。

---

## 10. 安全评估

| 攻击面 | 缓解 |
|---|---|
| 路径穿越 (`getOriginalFilename`) | 磁盘命名固定为 `<docId>.<ext>`，原名只入 DB |
| 上传 DoS | `max-file-size=25MB`，扩展名白名单，未来加 MIME 嗅探 |
| SSRF（网页抓取） | 仅允许公网 `http/https`；拒绝凭据、环回/私网/链路本地等地址；每次重定向重新校验；Spring AI reader 只读取下载后的内存资源，不直接打开用户 URL |
| SQL 注入 | JPA / 参数化 SQL，全部用 `?` 占位 |
| 凭证泄漏 | `application.yml` 默认值仅 dev 可用；prod `StartupValidator` fail-fast |
| 错误信息泄漏 | `GlobalExceptionHandler` 仅返 traceId，详情写日志 |
| CORS | 仅 `localhost:*`（dev）；生产同源不需 CORS |
| Swagger 暴露 | dev 默认开放；生产用 `springdoc.swagger-ui.enabled=false` |
| topK DoS | DTO `@Max(50)` + 服务层 clamp 双层防护 |
| Kafka JSON 反序列化 | `spring.json.trusted.packages=com.majm.rag.*`（建议收紧到 `ingestion.dto`） |

---

## 11. 关键决策记录（ADR）

| # | 决策 | 备选 | 选择理由 |
|---|---|---|---|
| 1 | 用 Kafka 异步摄入（非同步嵌入） | 同步 / RabbitMQ | 单次摄入耗时数秒～数分钟，需要解耦；Kafka 自带 partition + DLT |
| 2 | pgvector + Spring AI VectorStore（非独立向量库） | Milvus / Qdrant / Pinecone | 单实例规模够用，省一个组件；过滤可下推到 SQL `WHERE` |
| 3 | Spring AI BatchingStrategy → 自实现 `FixedSizeBatchingStrategy` | 用默认 TokenCountBatchingStrategy | 百炼硬限 batch ≤10，token-based 必败 |
| 4 | PROCESSING 短事务 + 行锁处理事务 + FAILED 短事务 | 全程无事务 / generation staging | 当前以连接与锁持有时间换取向量和 DONE 的原子性；staging 是后续扩展方向 |
| 5 | `IngestionStatusService` 独立 `@Service` | `IngestionConsumer` 内部 `@Transactional` 方法 | self-invocation bug |
| 6 | `vector_store` 独立表（V4） | 自定义 `document_chunk` 复用 PgVectorStore | PgVectorStore 只写 4 列，与自定义 NOT NULL 列冲突，是上线前 showstopper |
| 7 | `Conversation.@Version` + 滑窗 | 直接锁 / 无锁 | JSONB 行写入并发冲突；历史无限增长导致 token 爆炸 |
| 8 | actuator base-path = `/api/actuator` | 默认 `/actuator` | 与业务 API 同前缀，前端 Vite proxy 和 Nginx 不需要额外规则 |
| 9 | 前后端拆为两个 git repo | monorepo | 团队独立维护，发布节奏不同 |
| 10 | docker-java/Testcontainers 的 Docker Engine 29 兼容性问题 → 集成测试用本地 docker-compose | 强行 Testcontainers | 等上游修复，临时降级；集成测试覆盖度不变 |
| 11 | 上传事务 Outbox + Kafka 至少一次投递 | AFTER_COMMIT 事件监听器 / 分布式事务 | 消除提交后发送窗口；用幂等消费承接少量重复消息，复杂度低于 2PC |
| 12 | vector_store 生成文档列 + FK；处理事务持有文档行锁 | 事后补偿删除 / 仅应用锁 | 覆盖进程崩溃、跨实例并发和删除竞态，不依赖补偿一定执行 |

---

## 12. 关联文档

- 详细技术设计：`docs/architecture/TECHNICAL_DESIGN.md`
- 历史规划：`docs/superpowers/plans/`
- 历史设计稿：`docs/superpowers/specs/`
- 前端设计：`docs/superpowers/specs/2026-05-07-admin-platform-design.md`
