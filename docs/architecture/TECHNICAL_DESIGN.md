# 技术设计文档

**配套文档**: `SYSTEM_ARCHITECTURE.md`
**目标读者**: 接手开发、做扩展、做改造的工程师
**最后更新**: 2026-07-20

---

## 0. 文档约定

- 所有 Java 路径相对 `rag0429/src/main/java/com/majm/rag/`
- 所有 SQL 在 `rag0429/src/main/resources/db/migration/`
- 前端路径相对 `rag-admin/src/`
- 代码片段截取核心；完整请看 git

---

## 1. API 契约

### 1.1 知识库

| Method | Path | Body | Resp | 状态码 |
|---|---|---|---|---|
| GET | `/api/v1/knowledge-bases?page&size` | — | `Page<KnowledgeBaseResponse>` | 200 |
| GET | `/api/v1/knowledge-bases/{id}` | — | `KnowledgeBaseResponse` | 200 / 404 |
| POST | `/api/v1/knowledge-bases` | `CreateKnowledgeBaseRequest` | `KnowledgeBaseResponse` | 201 / 400 |
| PUT | `/api/v1/knowledge-bases/{id}` | `UpdateKnowledgeBaseRequest` | `KnowledgeBaseResponse` | 200 / 404 |
| DELETE | `/api/v1/knowledge-bases/{id}` | — | — | 204 / 404 |
| POST | `/api/v1/knowledge-bases/{id}/search` | `SearchKnowledgeBaseRequest` | `List<SearchResultItem>` | 200 |

### 1.2 文档

| Method | Path | Body | Resp | 状态码 |
|---|---|---|---|---|
| POST | `/api/v1/knowledge-bases/{kbId}/documents` | multipart `file` | `UploadDocumentResponse` | 202 / 400 / 413 |
| GET | `/api/v1/knowledge-bases/{kbId}/documents?page&size` | — | `Page<DocumentListItem>` | 200 |
| DELETE | `/api/v1/knowledge-bases/{kbId}/documents/{docId}` | — | — | 204 / 404 |
| GET | `/api/v1/knowledge-bases/{kbId}/documents/{docId}/chunks` | — | `List<DocumentChunkResponse>` | 200 / 404 |

### 1.3 对话

| Method | Path | Body | Resp | 状态码 |
|---|---|---|---|---|
| POST | `/api/v1/chat` | `ChatRequest` | `text/event-stream` (token 流) | 200 |
| POST | `/api/v1/chat/conversations` | `CreateConversationRequest` | `ConversationResponse` + `Location` | 201 |
| POST | `/api/v1/chat/conversations/{id}/messages` | `ConversationMessageRequest` | `text/event-stream` | 200 / 404 / 409 |

### 1.4 错误响应统一格式

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "timestamp": "2026-05-08T03:53:23Z",
  "traceId": "ffb84bbd",
  "fieldErrors": [{"field": "name", "message": "不能为空"}]
}
```

| code | HTTP | 触发 |
|---|---|---|
| `NOT_FOUND` | 404 | `ResourceNotFoundException` |
| `BAD_REQUEST` | 400 | `IllegalArgumentException` |
| `VALIDATION_FAILED` | 400 | `MethodArgumentNotValidException`（DTO 校验） |
| `PAYLOAD_TOO_LARGE` | 413 | `MaxUploadSizeExceededException` |
| `CONFLICT` | 409 | `DataIntegrityViolationException` |
| `CONCURRENT_UPDATE` | 409 | `ObjectOptimisticLockingFailureException` |
| `INTERNAL_ERROR` | 500 | 兜底 `Exception.class`（详情仅入日志） |
| `UNAUTHORIZED` | 401 | `ApiKeyFilter` |

### 1.5 DTO 规范

- Java 全部用 `record`（Spring Boot 3 / Java 21 习惯）
- 字段校验注解：`@NotBlank`、`@NotNull`、`@Min/@Max`、`@Size`、`@Valid`
- topK：`@Min(0) @Max(50)` + 紧凑构造器把 0 / 负数归一到 `DEFAULT_TOP_K=5`

### 1.6 SSE 流格式

由 `ChatSseEvents.wrap(ChatStream)` 包装为 `Flux<ServerSentEvent<String>>`，四类帧：

| event | data | 时机 |
|---|---|---|
| `context` | 本轮实际送入 LLM 的 `SearchResultItem[]` JSON | 首帧，只发送一次；单轮与多轮接口一致 |
| `token` | 单个 LLM token 文本 | 每个上游 `onNext` |
| `done` | 空字符串 | 流正常结束 |
| `error` | `ErrorResponse` JSON（`{code, message, timestamp, traceId}`） | 上游 `onError`；同时服务端 `log.error("[{traceId}] SSE stream failed", t)` 写完整堆栈 |

前端 `streamRequest`（`rag-admin/src/api/client.ts`）按事件类型分流：
- `context` → 解析 JSON 并附加到当前 assistant 消息；不再调用 `/search` 二次检索
- `token` → `controller.enqueue({type: "token", text: data})`（空字符串也保留）
- `done` → `controller.close()`
- `error` → `controller.error(new StreamServerError(payload))`，携带 `code` + `traceId`
- 兼容性：未知事件类型当 token 处理；`[DONE]` 旧 sentinel 仍被跳过
- 跨 chunk 边界缓冲（`buffer` 变量），CRLF 与 LF 都支持
- `useStreamingChat` 捕获 `StreamServerError` 后，把 `message（trace: xxxxxxxx）` 追加到当前 AI 消息尾部作为 `⚠️` 标记

测试：`ChatSseEventsTest` 覆盖 context 首帧、正常完成、上游异常和空流；前端
`api.client.test.ts` 覆盖 context/token/done/error、空 token、CRLF 与 TCP 碎片。

---

## 2. 数据访问层

### 2.1 Spring Data JPA Repository

```java
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Page<Document> findByKnowledgeBaseId(UUID kbId, Pageable pageable);

    @Query("SELECT d.filePath FROM Document d WHERE d.knowledgeBase.id = :kbId AND d.filePath IS NOT NULL")
    List<String> findFilePathsByKnowledgeBaseId(@Param("kbId") UUID kbId);
}
```

依赖 DB-level `ON DELETE CASCADE`：删除 KB 自动删 documents（V1 schema 已声明）。仓储不再有 `deleteByKnowledgeBaseId` 方法。

### 2.2 ChunkQueryService（JdbcTemplate 直查 vector_store）

`vector_store` 是 Spring AI 管理的表，没有对应 JPA 实体。元数据查询走 JdbcTemplate：

```java
@Service
public class ChunkQueryService {
    private static final String LIST_BY_DOC = """
        SELECT id, content, metadata FROM vector_store
         WHERE metadata->>'document_id' = ?
         ORDER BY (metadata->>'chunk_index')::int
        """;

    public List<DocumentChunkResponse> listByDocument(UUID documentId);
    public int deleteByDocument(UUID documentId);
    public int deleteByDocumentFromIndex(UUID documentId, int fromIndex);
    public int deleteByKnowledgeBase(UUID kbId);
}
```

`metadata` 是 JSONB，索引：
```sql
CREATE INDEX vector_store_doc_id_idx ON vector_store ((metadata->>'document_id'));
CREATE INDEX vector_store_kb_id_idx  ON vector_store ((metadata->>'knowledge_base_id'));
```

### 2.3 实体设计要点

#### Document.id 手动赋值

```java
@Entity
@Table(name = "document")
public class Document {
    @Id
    private UUID id;

    @PrePersist
    void assignIdIfMissing() {
        if (id == null) id = UUID.randomUUID();
    }
    ...
}
```

`DocumentUploadService.upload` 先 `UUID.randomUUID()`、再 `storageService.store(file, kbId, docId)` 落盘 `<docId>.ext`、最后 `documentRepository.save(doc)`。

**踩过的坑**：原本用 `@GeneratedValue(strategy = UUID)`，Hibernate 会忽略手动赋值生成新 UUID，导致磁盘文件名 ≠ DB 行 id。改用 `@PrePersist` 兼容两种调用方式。

#### Conversation.@Version

```java
@Entity
public class Conversation {
    @Id @GeneratedValue(strategy = UUID)
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, String>> messages = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;       // V5 加列 BIGINT NOT NULL DEFAULT 0
    ...
}
```

Hibernate 在 UPDATE 时附带 `WHERE version = ?`；版本不匹配抛 `ObjectOptimisticLockingFailureException`，handler 转 409。

---

## 3. 摄入流水线详细设计

### 3.1 状态机

```
                   upload         consume
   [创建]──────▶ PENDING ──────▶ PROCESSING
                                    │
                            ingest 成功 │ 失败
                                    ▼   ▼
                                 DONE  FAILED
                                  ▲       │
                                  │ 重新上传
                                  └───(新 doc)───
```

四个状态：
- `PENDING`：upload 写库后初始状态
- `PROCESSING`：consumer 拿到消息开始处理（每次 retry 也会重新置 PROCESSING）
- `DONE`：embedding 入库成功
- `FAILED`：解析或 embedding 失败，错误存 `error_message`

**注意**：retry 期间状态会在 PROCESSING ↔ FAILED 之间抖动（每次 retry 先 markProcessing，失败后 markFailed），3 次重试穷尽后写 DLT，状态稳定为 FAILED。前端轮询期间需要容忍这个抖动（轮询基于 status，最终一致即可）。

### 3.2 IngestionConsumer 实现要点

```java
@Component @RequiredArgsConstructor @Slf4j
public class IngestionConsumer {
    private final IngestionStatusService statusService;
    private final IngestionProcessor processor;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    public void consume(IngestionMessage msg) {
        ProcessingDecision decision = statusService.markProcessing(msg.documentId());
        if (decision != READY) return;                              // DONE / 已删除幂等跳过
        try {
            processor.process(msg);
        } catch (Exception e) {
            statusService.markFailed(msg.documentId(), e.getMessage());
            throw e;                                               // 让 Kafka 重试 + DLT
        }
    }
}

@Service @RequiredArgsConstructor
public class IngestionProcessor {
    @Transactional
    public Result process(IngestionMessage msg) {
        Document doc = documentRepository.findByIdForUpdate(msg.documentId())
            .orElse(null);
        if (doc == null) return MISSING;
        if (doc.getStatus() == DONE) return ALREADY_DONE;           // 等待行锁后的二次检查
        KnowledgeBase kb = doc.getKnowledgeBase();

        DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
        var rawDocs = reader.get();
        var splitter = new OverlappingTokenTextSplitter(kb.getChunkSize(), kb.getChunkOverlap());
        var chunks = splitter.apply(rawDocs);
        for (int i = 0; i < chunks.size(); i++) {
            // 复制原 metadata，并以 UUID.nameUUIDFromBytes(docId + ":" + i)
            // 生成稳定 ID 后构造待写入 chunk。
        }
        vectorStore.add(indexedChunks);                              // 稳定 ID upsert
        chunkQueryService.deleteByDocumentFromIndex(                 // 成功后裁剪旧尾部
            doc.getId(), indexedChunks.size());
        doc.setStatus(DONE);                                         // 与向量写入原子提交
        doc.setChunkCount(indexedChunks.size());
        doc.setErrorMessage(null);
        return COMPLETED;
    }
}
```

### 3.3 IngestionStatusService

```java
@Service @RequiredArgsConstructor
public class IngestionStatusService {
    private final DocumentRepository documentRepository;

    @Transactional public ProcessingDecision markProcessing(UUID id) { ... }
    @Transactional public boolean markFailed(UUID id, String msg) { ... }
}
```

两个方法都通过 `findByIdForUpdate` 获取悲观写锁。`markProcessing` 在短事务中提交
可观察的 `PROCESSING`；`markFailed` 与处理事务串行，并在看到 `DONE` 时保持成功状态。
`IngestionProcessor` 再次锁行，将 parser、embedding、向量 upsert/裁剪和 `DONE` 放入
一个事务，异常时整体回滚。依赖 JPA dirty checking，**没有显式 `save()`** 是有意为之。

### 3.4 KafkaConfig 重试策略

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> tpl) {
    var recoverer = new DeadLetterPublishingRecoverer(tpl);
    var backOff = new ExponentialBackOff(1000L, 2.0);   // 1s, 2s, 4s
    backOff.setMaxAttempts(3);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

主 topic 3 partition、1 replica；DLT 1 partition、1 replica（生产应该 ≥2）。
producer key = `documentId.toString()`，同一文档总是同一 partition、同一 consumer 实例处理，避免乱序。

### 3.5 上传到发消息：事务 Outbox

```java
@Service
public class DocumentUploadService {
    @Transactional
    public UploadDocumentResponse upload(UUID kbId, MultipartFile file) {
        validate(file);
        // ... 落盘 ...
        Document saved = documentRepository.saveAndFlush(doc);
        outboxRepository.enqueue(saved.getId(), kbId); // 与 document 同一 DB 事务
        return response(saved);
    }
}

@Service
public class IngestionOutboxPublisher {
    @Scheduled(fixedDelayString = "${app.ingestion.outbox.delay-ms:1000}")
    @Transactional
    public void publishReady() {
        for (var event : repository.lockReadyBatch(batchSize)) { // FOR UPDATE SKIP LOCKED
            try {
                kafkaTemplate.send(topic, event.documentId().toString(), message).get(timeout);
                repository.markPublished(event.id());
            } catch (Exception e) {
                repository.markRetry(event.id(), nextAttempt, nextAttemptAt, rootMessage(e));
            }
        }
    }
}
```

`document` 与 Outbox 事件原子提交，消除了 DB 已提交但 Kafka 发送进程崩溃造成的消息丢失窗口。
Outbox 仓储使用 JDBC，因此先 `saveAndFlush` 保证父记录 INSERT 已执行，再写带外键的
Outbox 行；二者仍由同一 Spring 事务提交或回滚。
发布失败保留 `PENDING`，记录 `attempt_count`、`next_attempt_at` 和 `last_error`，按
2s 起步、最高 300s 的指数退避重试。发布语义为 at-least-once，消费端通过 DONE
短路、文档行锁、确定性 chunk ID 和事务内 upsert 后裁剪保证重试幂等；V7 外键
阻止已删除文档产生孤儿向量。

---

## 4. 检索层

### 4.1 RetrievalService（多阶段后处理）

四阶段流水线：

```
向量召回（recall = finalTopK × expand-factor）
  ↓ Spring AI VectorStore.similaritySearch + KB 过滤
cross-encoder rerank（Aliyun gte-rerank）
  ↓ 写回 metadata.rerank_score
score-threshold 过滤
  ↓ 丢弃 < APP_RERANK_SCORE_THRESHOLD 的候选；无 rerank_score 的 fail-soft 保留
MMR 去冗余
  ↓ MmrDeduplicator.apply(ranked, finalTopK, λ)
返回 finalTopK
```

关键配置（`application.yml` → `app.rerank.*`）：

| 配置 | 默认 | 作用 |
|---|---|---|
| `expand-factor` | 2 | recall 多取 N 倍交给 rerank |
| `score-threshold` | -1.0 | 低于阈值的候选丢弃；-1.0 等价禁用 |
| `mmr-enabled` | true | 关闭则跳过 MMR，仅保留 rerank 顺序 |
| `mmr-lambda` | 0.7 | λ=1 纯相关性、λ=0 纯多样性；自动 clamp 到 `[0,1]` |

阈值过滤把所有候选都丢光时返回空 list — 让 LLM 走 `RAG_SYSTEM_PROMPT` 的"无法回答"分支，而不是用低相关 context 编造回答。

`MmrDeduplicator` 用 char-trigram Jaccard 估算 chunk 对相似度（无 embedding 往返成本）；ranked 列表本身的位置作为 relevance 的 fallback（无 rerank_score 时）。

KB 过滤仍通过 `FilterExpressionBuilder` 下推为 `WHERE metadata @> '{"knowledge_base_id":"..."}'`，让 HNSW + metadata 索引能 pre-filter。

### 4.2 查询重写与扩展（`com.majm.rag.retrieval.rewrite`）

在召回之前，`QueryRewriteService` 把用户原始 query 过一遍可配置的策略链。

`RewriteResult` 三字段：

| 字段 | 含义 |
|---|---|
| `originalQuery` | 用户真实意图（rerank 用它）；多轮 conversational 改写后会被替换 |
| `embeddingQuery` | 真正送进 `vectorStore.similaritySearch` 的文本；HyDE 用假设性段落，其它策略 = `originalQuery` |
| `expandedQueries` | 额外的查询变体（仅 Multi-Query 非空），触发 N 路召回 + RRF 融合 |

**三种策略**（`com.majm.rag.retrieval.rewrite.*Rewriter`）：

- **`ConversationalRewriter`** — 多轮指代消解。history 非空时一次 LLM 调用，把 `"那它有什么限制？"` + 上下文改写为 `"X 有什么限制？"`。history 为空 = 直接 passthrough，零 LLM 成本。
- **`HydeRewriter`** — 让 LLM 生成 2-4 句"假设性答案段落"，用它做 embedding 召回（HyDE/Gao et al. 2022）；rerank 仍用原 query，避免假设性内容污染相关性评分。
- **`MultiQueryRewriter`** — 让 LLM 输出 N 个改写问法（默认 N=3，配置范围 [2,8]），加上原 query 共 N+1 路召回，按 **RRF**（Reciprocal Rank Fusion）融合：`fused(d) = Σ_q 1 / (k + rank_q(d))`，k 默认 60。

**编排（`QueryRewriteService`）**：

- 配置 `app.query-rewrite.strategies=conversational,hyde`：先 conversational 改写出 standalone query，再把它送进 HyDE。两步链式合成，`merge()` 决定哪些字段被覆盖。
- 单 LLM 调用 budget = `timeout-ms`（默认 3000ms，最低 500ms）。整链跑在 `CompletableFuture` 里，`.get(timeoutMs, MS)` 超时 → fail-soft 回落原 query。
- 每个 `QueryRewriter` 内部也都 `try/catch` 包住 LLM 调用，自己先做一层 fail-soft，避免单点失败拖垮整链。
- `enabled=false` 或 strategies 全部不识别 → 整体 passthrough；INFO 日志在启动时打印 active chain。

**`RetrievalService` 改造**：

- 新增 `search(kbId, query, topK, RewriteResult)` 重载；老 `search(kbId, query, topK)` 仍存在（KB 直查端点用，不走 LLM 改写）。
- 没有 expansion 时走 `singleRecall(embeddingQuery)`；有 expansion 时 `multiQueryRecall` 跑 N+1 次 `similaritySearch`，按 RRF 融合后取 `recallSize` 个候选交给 rerank。
- 去重 key：`Document.getId()` 优先；没 id 时用 `text.hashCode():length` 作 fallback。

**配置（`app.query-rewrite.*`）**：

| 配置 | 默认 | 作用 |
|---|---|---|
| `enabled` | true | 总开关；false 立即 passthrough |
| `strategies` | `conversational,hyde` | 逗号分隔的策略链，按顺序应用 |
| `timeout-ms` | 3000（min 500） | 整链 wall-clock 预算 |
| `multi-query-count` | 3（clamp [2,8]） | Multi-Query 改写条数 |
| `rrf-k` | 60 | RRF 融合常数 |

测试：`QueryRewriteServiceTest`（10 case，含 timeout / 异常 / 未知策略 / 空 history）+ 三个 Rewriter 各自的单元测试（共 19 case）；`RetrievalServiceTest` 新增 RRF 融合 + HyDE 双 query 验证。

### 4.3 topK 防御

三层夹紧：
1. DTO `@Min(1) @Max(50)`（Bean Validation）
2. `ChatRequest` 紧凑构造器把 0/负数归一到 5
3. `RetrievalService.search` 内 `Math.min/max` clamp（防其他 caller）

`RetrievalLimits` 静态常量：`DEFAULT_TOP_K=5`、`MAX_TOP_K=50`。

---

## 5. 对话层

### 5.1 ChatService

```java
public class ChatService {
    static final String MSG_ROLE = "role", MSG_CONTENT = "content";
    static final String ROLE_USER = "user", ROLE_ASSISTANT = "assistant";

    @Value("${app.chat.max-history-messages:20}")
    private int maxHistoryMessages;

    static List<Map<String, String>> trimHistory(List<Map<String, String>> messages, int maxSize) {
        if (maxSize <= 0 || messages.size() <= maxSize) return messages;
        return new ArrayList<>(messages.subList(messages.size() - maxSize, messages.size()));
    }

    public ChatStream continueConversation(UUID id, ConversationMessageRequest req) {
        Conversation conv = conversationRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Conversation", id));
        RetrievedContext context = retrieveContext(
            conv.getKnowledgeBaseId(), req.question(), req.topK(), priorHistory);

        List<Map<String, String>> history = new ArrayList<>(conv.getMessages());
        history.add(Map.of(MSG_ROLE, ROLE_USER, MSG_CONTENT, req.question()));
        history = trimHistory(history, maxHistoryMessages);                  // 滑窗
        conv.setMessages(history);
        conversationRepository.save(conv);                                   // ← 触发 @Version 检查

        StringBuilder reply = new StringBuilder();
        Flux<String> tokens = chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context.promptText()))
            .messages(history.stream().map(this::toMessage).toList())
            .stream().content()
            .doOnNext(reply::append)
            .doOnComplete(() ->
                persistenceService.appendAssistantMessage(id, reply.toString()));
        return new ChatStream(context.items(), tokens);
    }
}
```

### 5.2 ConversationPersistenceService（独立 bean）

```java
@Service
class ConversationPersistenceService {
    private final ConversationRepository repo;

    @Value("${app.chat.max-history-messages:20}")
    private int maxHistoryMessages;

    @Transactional
    public void appendAssistantMessage(UUID id, String content) {
        repo.findById(id).ifPresent(conv -> {
            var messages = new ArrayList<>(conv.getMessages());
            messages.add(Map.of(ChatService.MSG_ROLE, ChatService.ROLE_ASSISTANT,
                                ChatService.MSG_CONTENT, content));
            conv.setMessages(ChatService.trimHistory(messages, maxHistoryMessages));
        });
    }
}
```

为何放独立 bean：被 `chatService` 的 `Flux.doOnComplete(...)` 调用，反应式回调里 `this.appendAssistantMessage` 会绕过 AOP 代理，`@Transactional` 失效；放在另一个 bean 里通过依赖注入调用走代理就 OK。

### 5.3 RAG_SYSTEM_PROMPT

```text
You are a helpful assistant. Answer questions based only on the provided context.
If the context does not contain enough information, say so clearly.

Context:
{context}
```

`{context}` 占位符在 `chatClient.prompt().system(s -> s.text(...).param("context", ctx))` 时由 Spring AI ST4 模板引擎替换。

---

## 6. 异常与日志

### 6.1 GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        String traceId = newTraceId();
        log.info("[{}] Resource not found: {}", traceId, ex.getMessage());
        return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", ex.getMessage(), traceId));
    }
    // ... IllegalArgument(400) / Validation(400) / MaxUpload(413) /
    //     DataIntegrity(409) / OptLock(409) / Exception.class(500)

    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
```

`Exception.class` 兜底返回 `INTERNAL_ERROR` + traceId，详情入 `log.error(traceId, ex)`。

### 6.2 traceId 跨日志关联

每次 ErrorResponse 生成 8 位 hex traceId。日志格式都带 `[{traceId}]` 前缀（便于 grep）。**不集成分布式 tracing**（Jaeger/Tempo），暂时只在单进程内串。

---

## 7. 安全控制

### 7.1 ApiKeyFilter

```java
@Configuration
public class ApiKeyFilter {
    private static final String GUARDED_PREFIX = "/api/v1/";
    private static final List<String> EXEMPT_PREFIXES = List.of(
        "/v3/api-docs", "/swagger-ui", "/swagger-resources", "/api/actuator/health");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> register(
            @Value("${app.security.api-key-auth.enabled:false}") boolean enabled,
            @Value("${app.security.api-key-auth.expected-key:}") String expectedKey) {
        // 启用时 expectedKey 必须非空，否则启动失败
        // 仅校验 /api/v1/** 下的请求；exempt 列表绕过
        // 不匹配返回 401 + JSON
    }
}
```

`/api/v1/**` 走过滤；actuator 不走（运维探针不该带 key）。

### 7.2 StartupValidator

`@PostConstruct` 在 `prod`/`production` profile 下校验：
- `spring.ai.openai.api-key` 不在 `{"", "dummy", "test", "changeme"}` 中（trim + lowercase 后比对）
- `spring.datasource.password` 不是 dev 默认 `rag`

任意一条不满足 → `IllegalStateException` → Spring 启动失败。
非 prod profile 一律跳过；`StartupValidatorTest`（7 case，含 `@ParameterizedTest` 多种占位 key + production 别名 + 混合 profile）锁定行为。

### 7.3 上传安全

```java
// LocalStorageService
String extension = sanitizedExtension(file.getOriginalFilename());  // 白名单 pdf/docx/doc/md/txt 才保留，否则降级 txt
Path dest = dir.resolve(documentId + "." + extension);              // 磁盘名固定
file.transferTo(dest);
```

```java
// DocumentUploadService.validate
if (file == null || file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
if (originalFilename == null || originalFilename.isBlank()) throw ...;
if (!ALLOWED_EXTENSIONS.contains(ext)) throw ...;
```

```yaml
spring.servlet.multipart.max-file-size: 25MB
spring.servlet.multipart.max-request-size: 30MB
```

---

## 8. 前端关键实现

### 8.1 SSE 行缓冲

跨 TCP chunk 边界时 `data: tok` + `en\n\n` 不能切两半：

```typescript
// rag-admin/src/api/client.ts
let buffer = '';
return new ReadableStream<string>({
    async pull(controller) {
        const { done, value } = await reader.read();
        if (done) {
            if (buffer) emitLines([buffer], controller);
            controller.close();
            return;
        }
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';        // 最后一个不完整行留在 buffer
        emitLines(lines, controller);
    },
    cancel() { reader.cancel(); }
});
```

3 个 Vitest 用例覆盖：单 chunk、跨边界、`[DONE]` sentinel。

### 8.2 useStreamingChat 的 aiMsgId 闭包修复

```typescript
const sendMessage = useCallback(async (opts): Promise<string> => {
    const aiMsgId = crypto.randomUUID();
    setMessages(prev => [...prev, userMsg, { id: aiMsgId, role: 'assistant', ... }]);
    try {
        const stream = await ...;
        for await (const event of stream) {
            setMessages(prev => prev.map(m => m.id !== aiMsgId ? m
                : event.type === 'context'
                    ? {...m, chunks: event.chunks}
                    : {...m, content: m.content + event.text}));
        }
    } catch { ... }
    return aiMsgId;                       // ← 返回给调用方
}, []);
```

`context` 与 token 属于同一 SSE 响应，且该 context 就是后端构造 prompt 时使用的
同一批检索结果。前端不再在流结束后调用 `/search`，因此查询重写、rerank 或索引变化
不会造成展示依据与实际回答依据不一致。

### 8.3 文档列表轮询

```typescript
// useDocList.ts
return useQuery({
    queryKey: docListKey(kbId),
    queryFn: () => listDocuments(kbId),
    refetchInterval: query => {
        const docs = query.state.data?.content ?? [];
        const hasActive = docs.some(d => ['PENDING', 'PROCESSING'].includes(d.status));
        return hasActive ? 3000 : false;          // 仅有活动 doc 时 3s 轮询，否则停
    },
});
```

页面组件不需要管定时器；TanStack Query 会自动起停。

### 8.4 健康指示器

```tsx
// AppLayout.tsx
useEffect(() => {
    const check = () => fetch('/api/actuator/health')
        .then(r => setHealthy(r.ok))
        .catch(() => setHealthy(false));
    check();
    const timer = setInterval(check, 30_000);
    return () => clearInterval(timer);
}, []);
```

后端 `management.endpoints.web.base-path=/api/actuator` 正好匹配前端这个路径。

---

## 9. 配置完整对照表

```yaml
spring:
  application:
    name: rag
  servlet.multipart:
    max-file-size: 25MB
    max-request-size: 30MB
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/rag}
    username: ${DB_USERNAME:rag}
    password: ${DB_PASSWORD:rag}
  jpa.hibernate.ddl-auto: none
  jpa.show-sql: false
  flyway.enabled: true
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: rag-ingestion
      auto-offset-reset: earliest
      properties.spring.json.trusted.packages: "com.majm.rag.*"
    producer.value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  ai:
    vectorstore.pgvector:
      index-type: HNSW
      distance-type: COSINE_DISTANCE
      dimensions: 1024
      initialize-schema: false
    openai:
      base-url: ${BAILIAN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}
      api-key: ${DASHSCOPE_API_KEY:${OPENAI_API_KEY:dummy}}
      chat.options.model: ${BAILIAN_CHAT_MODEL:qwen-plus}
      embedding.options.model: ${BAILIAN_EMBEDDING_MODEL:text-embedding-v3}
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat.options.model: llama3.2

app:
  storage.base-path: ${user.home}/rag-uploads
  ingestion:
    topic: document.ingestion
    dlt-topic: document.ingestion.dlt
    outbox:
      delay-ms: ${APP_INGESTION_OUTBOX_DELAY_MS:1000}
      batch-size: ${APP_INGESTION_OUTBOX_BATCH_SIZE:20}
      send-timeout-ms: ${APP_INGESTION_OUTBOX_SEND_TIMEOUT_MS:10000}
  security.api-key-auth:
    enabled: ${API_KEY_AUTH_ENABLED:false}
    expected-key: ${API_KEY:}
  chat.max-history-messages: ${CHAT_MAX_HISTORY_MESSAGES:20}
  embedding.batch-size: ${APP_EMBEDDING_BATCH_SIZE:10}

server.port: 8080

management:
  endpoints.web:
    base-path: /api/actuator
    exposure.include: health
  endpoint.health:
    show-details: when-authorized
    probes.enabled: true
  health:
    livenessstate.enabled: true
    readinessstate.enabled: true

springdoc:
  api-docs.path: /v3/api-docs
  swagger-ui.path: /swagger-ui.html
```

---

## 10. 数据库迁移

| Version | 文件 | 作用 |
|---|---|---|
| V1 | `V1__init_schema.sql` | 建初始表（`knowledge_base`, `document`, `conversation`，含 `vector` 扩展和 `uuid-ossp`） |
| V2 | `V2__create_hnsw_index.sql` | 在原 `document_chunk` 上建 HNSW（已废弃但留迁移记录） |
| V3 | `V3__update_embedding_dimension_for_bailian.sql` | 1536 → 1024 维（百炼 text-embedding-v3） |
| V4 | `V4__add_vector_store_table.sql` | 建 Spring AI 标准 `vector_store` 表 + HNSW 索引；DROP 原 `document_chunk` |
| V5 | `V5__add_conversation_version.sql` | `conversation` 加 `version BIGINT NOT NULL DEFAULT 0`（乐观锁） |
| V6 | `V6__add_ingestion_outbox_and_chunk_uniqueness.sql` | 新增 `ingestion_outbox`；清理重复向量并为 `(document_id, chunk_index)` 建唯一索引 |
| V7 | `V7__enforce_vector_document_integrity.sql` | 清理孤儿向量；增加生成 `document_id`、文档外键与级联删除 |

**迁移原则**：
- 单向（不写 down 脚本，prod 走 backup + redeploy）
- 改 vector 维度 = 数据销毁性变更，必须单独主版本通告
- Flyway 启动时执行；测试环境 docker 起来后跑 `mvn test` 时自动迁移

---

## 11. 测试策略

### 11.1 后端

| 测试 | 类型 | 范围 |
|---|---|---|
| `DocumentParserFactoryTest` | unit | 文件类型 → reader 选择；URL 分支已删（SSRF 防御） |
| `DocumentUploadServiceTest` | unit | 校验路径、同事务 Outbox 入队、empty/unsupported 场景 |
| `ChatServiceTest` | unit | 单轮/多轮检索结果同时用于 prompt 与 SSE context |
| `KnowledgeBaseServiceTest` | unit | CRUD、ResourceNotFound、delete 路径（磁盘清理 + 缺失 KB 短路） |
| `FixedSizeBatchingStrategyTest` | unit | 分批边界（整除、余数、空、null、负数、顺序） |
| `MmrDeduplicatorTest` | unit | λ=0 / λ=1 / 中间值、近重复丢弃、clamp、fallback relevance |
| `RetrievalServiceTest` | unit | recall→rerank→threshold→MMR 全链路；expand-factor、阈值清空、缺 rerank_score |
| `ChatSseEventsTest` | unit | context/token/done/error 四类帧；流首异常；空流 |
| `IngestionOutboxPublisherTest` | unit | 发布成功、失败记录与退避重试 |
| `IngestionConsumerTest` | unit | typed claim 编排、DONE/删除跳过、事务失败落库 |
| `IngestionProcessorTest` | unit | 行锁二次检查、稳定 chunk ID、事务内 upsert/裁剪/完成 |
| `IngestionStatusServiceTest` | unit | 加锁状态迁移、retry 清理旧错误、DONE 不被迟到失败覆盖 |
| `DocumentServiceTest` | unit | 文档列表返回持久化的摄入失败原因 |
| `StartupValidatorTest` | unit | prod profile 占位 key、dev 默认密码、非 prod 跳过、production 别名 |
| `IngestionConsumerIntegrationTest` | integration | 本地 PG/Kafka + WireMock；happy/failure、V7 外键、并发串行、删除竞态 |

总数 ~40 个（参数化展开后更多），CI 时间 ~12s（不含集成测试 5s 额外）。

### 11.2 前端

| 测试 | 类型 | 范围 |
|---|---|---|
| `api.client.test.ts` | unit (Vitest) | request 200/204/4xx；streamRequest 单 chunk / 跨 chunk / [DONE]；event 类型路由（token / done / error）；空 token 保留；CRLF；TCP 碎片不挂起 |

总数 12 个。

### 11.3 集成测试基础设施限制

`IngestionConsumerIntegrationTest` 用本地 docker-compose 而不是 Testcontainers。原因：Docker Engine 29 + Docker Desktop 4.53 的 user-context proxy 对 docker-java 的 HTTP 探测返回 stub（curl 同 socket 工作正常）。试过 testcontainers 1.21.3 + docker-java 3.5.0 都没绕过去。

后续计划：等 Testcontainers 上游修好兼容性，把 `requireLocalInfra` 那段换成 `@Container` + `@ServiceConnection`。

---

## 12. 性能与容量

### 12.1 当前性能基线（粗估，未严格压测）

| 操作 | 延迟 | 说明 |
|---|---|---|
| KB CRUD | <50ms | 简单 JPA |
| 文档上传（API 返回） | <200ms | 落盘 + document/outbox 两次 INSERT；Kafka 异步发布 |
| 文档摄入（端到端） | 5-30s | 视文档大小，主要花在 Tika + embedding API |
| 单次 RAG chat（首 token） | 1-2s | 一次 embedding（query）+ 一次 ANN + LLM TTFT |
| 单次 RAG chat（完整） | 5-15s | LLM 流式输出长度决定 |

### 12.2 已知瓶颈

1. **Embedding API 调用**：百炼批量 ≤10，超过就要多次往返。一个 100 chunk 的文档 = 10 次串行 HTTP 调用。改进：并发批量（Spring AI 不直接支持，需自定义 `EmbeddingModel` 装饰器）。
2. **Conversation JSONB 全量重写**：每轮对话整列覆写。N 条历史 → O(N) 字节传输。已用滑窗 cap=20 控制总字节量。
3. **HNSW `ef_search` 默认 40**：大 KB（>10w chunk）召回率会下降。改进：会话级 `SET hnsw.ef_search = 100`。
4. **Tika 文档解析**：大 PDF 一次性加载内存。文件上限 25MB 已防最坏情况。

### 12.3 连接池

HikariCP 默认 10 连接。Kafka consumer concurrency=1，长事务已拆为短事务，连接池压力小。建议 prod 提到 20。

---

## 13. 运维与故障排查

### 13.1 常见问题速查

| 现象 | 排查 | 修复 |
|---|---|---|
| 文档卡 PROCESSING | DLT 是否有消息 / consumer 日志 | 看 errorMessage；改后重传 |
| 文档全部 FAILED | 检查 LLM API key、网络、batch-size 配置 | 调对应 env |
| `/api/actuator/health` 返 DOWN | PG / Kafka 连不上 | docker-compose ps |
| 上传 413 | 文件 > 25MB | 拆文件或调 multipart 上限（生产慎重） |
| Chat 流中断 | 前端消息显示结构化流错误 | 用消息中的 traceId 查询后端日志 |
| 删除 KB 后文件还在 | StorageService.delete 失败（warn 日志） | 手动 rm -rf `~/rag-uploads/<kbId>/` |

### 13.2 日志关键字

```
Ingestion failed for document  -> 摄入失败，每次 retry 一行
Optimistic lock conflict       -> 多端同时改 conversation
StartupValidator               -> 启动期凭证校验
[<traceId>]                    -> ErrorResponse 关联
```

### 13.3 常用 SQL

```sql
-- 看摄入状态分布
SELECT status, COUNT(*) FROM document GROUP BY status;

-- 看某 KB 的 vector_store 行数
SELECT COUNT(*) FROM vector_store WHERE metadata->>'knowledge_base_id' = '<kbId>';

-- 找孤儿向量（document_id 不在 document 表）
SELECT v.id FROM vector_store v
LEFT JOIN document d ON d.id::text = v.metadata->>'document_id'
WHERE d.id IS NULL LIMIT 10;

-- 强制重建 HNSW
REINDEX INDEX vector_store_embedding_hnsw_idx;
```

---

## 14. 扩展指南

### 14.1 加新文档类型

1. `DocumentParserFactory.create` 加 `case`
2. `DocumentUploadService.ALLOWED_EXTENSIONS` 加扩展名
3. `LocalStorageService.ALLOWED_EXTENSIONS` 加同名
4. 加单测 `DocumentParserFactoryTest.create_xxxType_returnsXxxReader`

### 14.2 切换 LLM provider

1. `application.yml` 改 `spring.ai.openai.{base-url, api-key, chat.options.model, embedding.options.model}`
2. 改 `spring.ai.vectorstore.pgvector.dimensions` 和 V?-migration（重建 vector_store）
3. 调 `app.embedding.batch-size`（OpenAI ~2048，DashScope 10）
4. prod 重启时 `StartupValidator` 会先校验

### 14.3 加新 endpoint

惯例：
1. controller 在对应 `xx/controller/`
2. service 在 `xx/`
3. DTO 用 `record`
4. 找不到资源 → `throw ResourceNotFoundException.of("X", id)`
5. 只读用 `@Transactional(readOnly = true)`
6. controller 不直接持有 Repository（KbController/DocumentController 都已是薄壳）

---

## 15. 附录：版本快照

```
Java:         21
Spring Boot:  3.3.5
Spring AI:    1.1.5
PostgreSQL:   18 + pgvector
Kafka:        3.8.0
Tika:         3.1.0
```

发布前验证命令：

```bash
./mvnw test
cd ../rag-admin
pnpm test -- --run
pnpm lint
pnpm build
```
