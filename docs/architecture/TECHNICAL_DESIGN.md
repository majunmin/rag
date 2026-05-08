# 技术设计文档

**配套文档**: `SYSTEM_ARCHITECTURE.md`
**目标读者**: 接手开发、做扩展、做改造的工程师
**最后更新**: 2026-05-08

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

当前实现：`Flux<String>` 直接返回，每条 `data: <token>` + 空行。无 `event:` 字段，无 `[DONE]` sentinel。

前端 `streamRequest` 解析：
- 跨 chunk 边界缓冲（`buffer` 变量）
- 行尾 `\n` 切分
- 仅取 `data: ` 前缀的行
- 跳过 `[DONE]`（兼容兼容）
- 出错时整个流被前端 catch，UI 显示"连接中断，请重试"

**待改进**：增加 `event: error` 事件携带 traceId，让客户端能展示具体原因。

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
    private static final int MIN_CHUNK_SIZE = 5;
    private static final int MAX_CHUNK_SIZE = 10000;
    private static final boolean KEEP_SEPARATOR = true;

    private final KnowledgeBaseService kbService;
    private final IngestionStatusService statusService;
    private final DocumentParserFactory parserFactory;
    private final VectorStore vectorStore;

    @KafkaListener(topics = "${app.ingestion.topic:document.ingestion}",
                   groupId = "${spring.kafka.consumer.group-id:rag-ingestion}")
    public void consume(IngestionMessage msg) {                    // 注意：consume 没有 @Transactional
        Document doc = statusService.markProcessing(msg.documentId());
        KnowledgeBase kb = kbService.getById(msg.knowledgeBaseId());
        try {
            int chunkCount = ingest(doc, kb);                       // 无事务
            statusService.markDone(doc.getId(), chunkCount);
        } catch (Exception e) {
            statusService.markFailed(doc.getId(), e.getMessage());
            throw e;                                                // 让 Kafka 重试 + DLT
        }
    }

    private int ingest(Document doc, KnowledgeBase kb) {
        DocumentReader reader = parserFactory.create(doc.getFileType(), doc.getFilePath());
        var rawDocs = reader.get();
        var splitter = new TokenTextSplitter(kb.getChunkSize(), kb.getChunkOverlap(),
                                              MIN_CHUNK_SIZE, MAX_CHUNK_SIZE, KEEP_SEPARATOR);
        var chunks = splitter.apply(rawDocs);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().putAll(Map.of(
                "knowledge_base_id", kb.getId().toString(),
                "document_id", doc.getId().toString(),
                "document_name", doc.getName(),
                "chunk_index", i));
        }
        vectorStore.add(chunks);                                    // 含 FixedSizeBatchingStrategy
        return chunks.size();
    }
}
```

### 3.3 IngestionStatusService

```java
@Service @RequiredArgsConstructor
public class IngestionStatusService {
    private final DocumentRepository documentRepository;

    @Transactional public Document markProcessing(UUID id) { ... doc.setStatus(PROCESSING); return doc; }
    @Transactional public void markDone(UUID id, int count) { ... doc.setStatus(DONE); doc.setChunkCount(count); doc.setErrorMessage(null); }
    @Transactional public void markFailed(UUID id, String msg) { ... doc.setStatus(FAILED); doc.setErrorMessage(msg); }
}
```

依赖 JPA dirty checking：`findById` 返回 managed 实体 → setter 修改 → 事务提交时 Hibernate 自动 flush UPDATE。**没有显式 `save()`** 是有意为之（符合 P3C），见 SYSTEM_ARCHITECTURE.md ADR #5。

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

### 3.5 上传到发消息：AFTER_COMMIT 模式

```java
@Service
public class DocumentUploadService {
    @Transactional
    public UploadDocumentResponse upload(UUID kbId, MultipartFile file) {
        validate(file);
        // ... 落盘 + save ...
        eventPublisher.publishEvent(new IngestionRequestedEvent(docId, kbId));
        // 返回，事务提交
    }
}

@Component
public class IngestionEventListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestionRequested(IngestionRequestedEvent evt) {
        kafkaTemplate.send(ingestionTopic, evt.documentId().toString(), new IngestionMessage(...));
    }
}
```

DB 提交后才发消息：DB 回滚时 Kafka 上不会留下幽灵消息；同步发送线程也不在 DB 事务里。

---

## 4. 检索层

### 4.1 RetrievalService

```java
@Service @RequiredArgsConstructor
public class RetrievalService {
    private static final String KNOWLEDGE_BASE_ID_FILTER = "knowledge_base_id";
    private final VectorStore vectorStore;

    public List<Document> search(UUID kbId, String query, int topK) {
        int clamped = Math.min(Math.max(topK, 1), RetrievalLimits.MAX_TOP_K);  // [1,50]
        var filter = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(SearchRequest.builder()
            .query(query).topK(clamped)
            .filterExpression(filter.eq(KNOWLEDGE_BASE_ID_FILTER, kbId.toString()).build())
            .build());
    }
}
```

`FilterExpressionBuilder` 把 KB 过滤翻译为 `WHERE metadata @> '{"knowledge_base_id":"..."}'`，下推到 SQL，让 HNSW 索引能用上 metadata GIN-style 索引做 pre-filter。

### 4.2 topK 防御

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

    public Flux<String> continueConversation(UUID id, ConversationMessageRequest req) {
        Conversation conv = conversationRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Conversation", id));
        String context = buildContext(conv.getKnowledgeBaseId(), req.question(), req.topK());

        List<Map<String, String>> history = new ArrayList<>(conv.getMessages());
        history.add(Map.of(MSG_ROLE, ROLE_USER, MSG_CONTENT, req.question()));
        history = trimHistory(history, maxHistoryMessages);                  // 滑窗
        conv.setMessages(history);
        conversationRepository.save(conv);                                   // ← 触发 @Version 检查

        StringBuilder reply = new StringBuilder();
        return chatClient.prompt()
            .system(s -> s.text(RAG_SYSTEM_PROMPT).param("context", context))
            .messages(history.stream().map(this::toMessage).toList())
            .stream().content()
            .doOnNext(reply::append)
            .doOnComplete(() ->
                persistenceService.appendAssistantMessage(id, reply.toString()));
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
- `spring.ai.openai.api-key` 不在 `{"", "dummy", "test", "changeme"}` 中
- `spring.datasource.password` 不是 dev 默认 `rag`

任意一条不满足 → `IllegalStateException` → Spring 启动失败。

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
        for await tokens append: setMessages(prev => prev.map(m => m.id === aiMsgId ? {...m, content: m.content + value} : m));
    } catch { ... }
    return aiMsgId;                       // ← 返回给调用方
}, []);

// ChatPage.tsx
const aiMsgId = await sendMessage({...});
const chunks = await searchKb(kbId, q, topK);
attachChunks(aiMsgId, chunks);            // 不依赖闭包里的 messages 快照
```

之前 bug：`[...messages].reverse().find(m => m.role === 'assistant')` 拿到的是发送前的 messages 快照，会 attach 到上一条或未定义。

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
| `DocumentUploadServiceTest` | unit | 校验路径，事件发布，empty/unsupported 场景 |
| `ChatServiceTest` | unit | `buildContext`（package-private 暴露给测试） |
| `KnowledgeBaseServiceTest` | unit | CRUD、ResourceNotFound 抛出 |
| `FixedSizeBatchingStrategyTest` | unit | 分批边界（整除、余数、空、null、负数、顺序） |
| `IngestionConsumerIntegrationTest` | integration | `@SpringBootTest` + 本地 PG/Kafka + WireMock；happy + failure path |

总数 23 个，CI 时间 ~10s（不含集成测试 5s 额外）。

### 11.2 前端

| 测试 | 类型 | 范围 |
|---|---|---|
| `api.client.test.ts` | unit (Vitest) | request 200/204/4xx；streamRequest 单 chunk / 跨 chunk / [DONE] |

总数 7 个。

### 11.3 集成测试基础设施限制

`IngestionConsumerIntegrationTest` 用本地 docker-compose 而不是 Testcontainers。原因：Docker Engine 29 + Docker Desktop 4.53 的 user-context proxy 对 docker-java 的 HTTP 探测返回 stub（curl 同 socket 工作正常）。试过 testcontainers 1.21.3 + docker-java 3.5.0 都没绕过去。

后续计划：等 Testcontainers 上游修好兼容性，把 `requireLocalInfra` 那段换成 `@Container` + `@ServiceConnection`。

---

## 12. 性能与容量

### 12.1 当前性能基线（粗估，未严格压测）

| 操作 | 延迟 | 说明 |
|---|---|---|
| KB CRUD | <50ms | 简单 JPA |
| 文档上传（API 返回） | <200ms | 落盘 + 一次 INSERT + Kafka send |
| 文档摄入（端到端） | 5-30s | 视文档大小，主要花在 Tika + embedding API |
| 单次 RAG chat（首 token） | 1-2s | 一次 embedding（query）+ 一次 ANN + LLM TTFT |
| ���次 RAG chat（完整） | 5-15s | LLM 流式输出长度决定 |

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
| Chat 流半截卡住 | 后端异常截断 SSE | 看后端日志 traceId（待改进：发 `event: error`） |
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
git HEAD:     00804ec  fix(ingestion): cap embedding batch size at 10 for Aliyun DashScope
Java:         21
Spring Boot:  3.3.5
Spring AI:    1.0.0
PostgreSQL:   18 + pgvector
Kafka:        3.8.0
Tika:         3.1.0
```

测试结果（最近一次 `mvn test`）：

```
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
```

前端（`pnpm test --run`）：

```
Test Files  1 passed (1)
     Tests  7 passed (7)
```
