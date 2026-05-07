# 上线前修复计划

**Date:** 2026-05-07
**Source:** 后端审查报告 (3-agent review)
**Goal:** 在上线前修复 3 类必须问题：核心流程 showstoppers / 安全加固 / 错误路径与 DTO 一致性

> 三个 Workflow 顺序执行。每个 Workflow 内部任务串行；Workflow ���成后单独可发布。

---

## Workflow 1 — 核心流程 Showstoppers

当前 ingestion 流程根本跑不通；失败状态永远不写入；ingestion 事务边界过宽。

### Task 1.1 PgVectorStore schema 修复

**问题**: `document_chunk` 表 NOT NULL `document_id/knowledge_base_id/chunk_index`，但 PgVectorStore 只插入 `id/content/metadata/embedding`，第一次 ingestion 即失败。

**方案**: 让 PgVectorStore 使用 Spring AI 标准表 `vector_store`；删除 JPA `DocumentChunk` 实体；chunks 列表/删除走 PgVectorStore API（按 metadata 过滤）。

**改动**:
- 新增 `db/migration/V4__add_vector_store_table.sql`：建 `vector_store` 表 + HNSW 索引；保留 `document_chunk` 表但允许之后清理
- `config/VectorStoreConfig.java`：移除 `vectorTableName("document_chunk")`，使用默认表名
- 删除 `knowledge/domain/DocumentChunk.java`
- 删除 `knowledge/DocumentChunkRepository.java`
- `knowledge/DocumentController.java`: `chunks()` 改为基于 JdbcTemplate 查询 `vector_store` 按 `metadata->>'document_id'` 过滤；`delete()` 改为调用 `vectorStore.delete(Filter)`
- `knowledge/KnowledgeBaseService.java`: `delete()` 改为先 `vectorStore.delete(filter by kbId)` 再 DB 级联删
- 新增 `knowledge/dto/DocumentChunkResponse.java`（顺便修 #13 实体泄漏）

### Task 1.2 IngestionConsumer 失败状态 + 事务边界

**问题**: `saveFailureStatus` 自调用绕过 AOP；FAILED 永远不写入。整个 `consume` 一个大事务，HTTP 调用占用 DB 连接。

**方案**:
1. 抽 `IngestionStatusService`（独立 `@Service`），方法 public，封装 PROCESSING / DONE / FAILED 三种状态写入
2. 拆 `consume`：
   - 短事务: mark PROCESSING
   - 非事务区: parser → splitter → embedding → vectorStore.add
   - 短事务: mark DONE / FAILED

**改动**:
- 新增 `ingestion/IngestionStatusService.java`
- `ingestion/IngestionConsumer.java`：去掉 `@Transactional`，改用 service

### Verification

- `./mvnw compile` 通过
- 起 PG + Kafka，上传 PDF：状态 PENDING → PROCESSING → DONE，chunk_count > 0
- `vector_store` 表里能看到记录
- 故意构造 parse 失败：status 变 FAILED，errorMessage 有值

---

## Workflow 2 — 安全加固

### Task 2.1 文件上传安全

**问题**: 路径穿越 (`getOriginalFilename` 直接 resolve)；无大小限制；只按扩展名判类型。

**改动**:
- `ingestion/LocalStorageService.java`：磁盘命名改 `<documentId>.<ext>`，ext 从原文件名安全提取（白名单）
- `application.yml`：加 `spring.servlet.multipart.{max-file-size, max-request-size}`
- `ingestion/DocumentUploadService.java`：拒绝 null/empty 文件名；强制扩展名白名单（pdf/docx/doc/md/txt）

### Task 2.2 关闭 SSRF 通道

**改动**: `ingestion/DocumentParserFactory.java`：删除 `URL` 分支。

### Task 2.3 凭证与配置加固

**改动**:
- `application.yml`：
  - `datasource.password: ${DB_PASSWORD}`（无默认）
  - `ai.openai.api-key: ${DASHSCOPE_API_KEY:}`（默认空字符串而非 `dummy`）
- 新增 `config/StartupValidator.java`：`@PostConstruct` 在非 `dev` profile 下校验 api-key 非空且不为 `dummy`

### Task 2.4 简单 API Key 鉴权

**改动**: 新增 `config/ApiKeyFilter.java`：从 `app.security.api-key` 读期望值；对 `/api/v1/**` 校验 `X-API-Key` header；可通过 profile `dev` 关闭。

### Verification

- 上传超大文件 → 413
- 上传 `../etc/passwd` 文件名 → 文件落盘为 `<uuid>.txt`，不逃逸
- 上传 `.exe` → 400
- 不设 DB_PASSWORD 启动 → fail-fast
- 设 api-key=dummy 在非 dev profile → fail-fast
- `/api/v1/knowledge-bases` 不带 `X-API-Key` → 401（dev profile 下放行）

---

## Workflow 3 — 错误路径与 DTO

### Task 3.1 异常处理体系

**改动**:
- 新增 `common/exception/ResourceNotFoundException.java`
- 新增 `common/dto/ErrorResponse.java`（record，含 code/message/timestamp/fieldErrors/traceId）
- 重写 `common/GlobalExceptionHandler.java`：
  - `ResourceNotFoundException` → 404
  - `IllegalArgumentException` → 400
  - `MethodArgumentNotValidException` → 400 + fieldErrors
  - `MaxUploadSizeExceededException` → 413
  - `DataIntegrityViolationException` → 409
  - `Exception.class` → 500，仅日志记录 detail，响应只含 traceId
- 业务层把 `IllegalArgumentException("not found")` 改为 `ResourceNotFoundException`

### Task 3.2 Kafka send-after-commit

**改动**: `ingestion/DocumentUploadService.java`：
- 把 `kafkaTemplate.send(...)` 拆出，包成 `ApplicationEvent` 发布
- 新增 `IngestionEventListener`，`@TransactionalEventListener(AFTER_COMMIT)` 监听后发 Kafka

### Task 3.3 ConversationResponse DTO

**改动**:
- 新增 `chat/dto/ConversationResponse.java`
- `chat/ChatController.java::createConversation`：返回 `ResponseEntity.created(uri).body(ConversationResponse.from(...))`

### Verification

- KB 不存在 → 404 + 结构化 body
- topK 校验失败 → 400 + fieldErrors
- 上传超大 → 413
- 重复 KB（如果加了 unique）→ 409
- 故意制造 NPE → 500，body 仅含 traceId，stack trace 在日志

---

## 不在本次范围

显式推迟到下一轮：
- #7 Conversation 并发竞态（@Version）+ 历史窗口
- #8 topK `@Max` 上限（容易加，但需要 DTO 校验链路改动，独立做）
- #9 删除链路重构（去掉手动 cascade，DB cascade 接管，加文件清理）
- #13 抽 DocumentService（架构重构）
- #15 ModelRouter / embeddingModel 字段二选一
- #16 IngestionConsumer 集成测试（testcontainers）

这些不影响"能跑、安全、错误可观测"，作为第二批迭代。
