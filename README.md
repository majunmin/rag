# RAG 知识库后端 (rag0429)

基于检索增强生成 (RAG) 的知识库问答服务。文档异步摄入、向量化存储，
对话时按语义召回相关 chunk 与问题一起送入 LLM 生成答案，支持多轮对话。

配套前端：[`rag-admin`](../rag-admin)（独立仓库）

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / JVM | Java 21 (Temurin) |
| 框架 | Spring Boot 3.3.5 |
| AI / 向量检索 | Spring AI 1.1.5 + pgvector |
| 数据库 | PostgreSQL 18 + pgvector |
| 消息队列 | Apache Kafka 3.8 (KRaft) |
| LLM 服务 | 阿里云百炼 (DashScope) — `qwen-plus` + `text-embedding-v3` |
| 文档解析 | Spring AI Document Readers 1.1.5 + Apache Tika 3.3 |
| 构建 | Maven 3.9 + Maven Wrapper |
| 容器 | Docker (multi-stage Alpine) |

详细架构与设计：见 [`docs/architecture/`](docs/architecture/)。

---

## 快速开始（本地开发）

### 前置依赖

- JDK 21
- Docker / Docker Compose
- 阿里云百炼 API Key（`DASHSCOPE_API_KEY`）

### 启动

```bash
# 1. 起 PostgreSQL + Kafka
docker compose up -d

# 2. 设置 LLM API Key
export DASHSCOPE_API_KEY=sk-xxx

# 3. 启动后端（默认 dev profile）
./mvnw spring-boot:run
```

成功后：
- API: http://localhost:8080/api/v1
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/api/actuator/health

### 测试

```bash
./mvnw test                    # 全部测试 (~20s, 含集成测试)
./mvnw test -Dtest='!IngestionConsumerIntegrationTest'   # 只跑单元测试
```

集成测试依赖本地 docker-compose 栈（pg + kafka 端口可达）；不可达时
自动 skip。

---

## 运行模式 (Spring Profile)

| Profile | 用途 | 特点 |
|---|---|---|
| **dev** *(默认)* | 本地开发 | Swagger 启用、彩色控制台日志、宽松校验 |
| **prod** | 生产环境 | JSON 日志、Swagger 关闭、HikariCP tuned、`StartupValidator` 强制 env 变量非占位值 |

切换方式：`SPRING_PROFILES_ACTIVE=prod`，或 Docker 镜像默认即 `prod`。

### 关键 env 变量

| 变量 | 必需 | 说明 |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | prod 必需 | Postgres 连接；prod 拒绝 dev 默认值 `rag` |
| `DASHSCOPE_API_KEY` | prod 必需 | 百炼 API Key；prod 拒绝 `dummy`/空 |
| `KAFKA_BOOTSTRAP_SERVERS` | 否 | 默认 `localhost:9092` |
| `APP_STORAGE_BASE_PATH` | 否 | 默认 `~/rag-uploads`；Docker 镜像默认 `/app/uploads` |
| `API_KEY_AUTH_ENABLED` + `API_KEY` | 否 | 启用 `X-API-Key` 鉴权（prod 推荐） |
| `CHAT_MAX_HISTORY_MESSAGES` | 否 | 多轮对话历史窗口，默认 20 |
| `APP_EMBEDDING_BATCH_SIZE` | 否 | embedding 批量大小，默认 10（百炼上限），OpenAI 可设 2048 |
| `APP_INGESTION_OUTBOX_DELAY_MS` | 否 | Outbox 扫描间隔，默认 1000ms |
| `APP_INGESTION_OUTBOX_BATCH_SIZE` | 否 | 每次锁定并发布的 Outbox 事件数，默认 20，范围 1-100 |
| `APP_INGESTION_OUTBOX_SEND_TIMEOUT_MS` | 否 | 单条 Kafka 发送确认超时，默认 10000ms |

完整列表：见 [`src/main/resources/application.yml`](src/main/resources/application.yml)
和 [`docs/architecture/TECHNICAL_DESIGN.md`](docs/architecture/TECHNICAL_DESIGN.md)。

---

## Docker 部署

```bash
docker build -t rag:local .

docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/rag \
  -e DB_PASSWORD=<real-password> \
  -e DASHSCOPE_API_KEY=<sk-xxx> \
  -v rag-uploads:/app/uploads \
  rag:local
```

镜像特性：
- 多阶段构建（jdk-alpine 编译 + jre-alpine 运行）
- 非 root 用户 `rag`
- `tini` 处理 PID 1 信号
- `HEALTHCHECK` 探测 `/api/actuator/health/liveness`
- 默认 `SPRING_PROFILES_ACTIVE=prod`

---

## 项目结构

```
rag0429/
├── src/main/java/com/majm/rag/
│   ├── chat/                  对话域（SSE 流式 + 多轮）
│   ├── knowledge/             知识库 + 文档 CRUD + 检索
│   ├── ingestion/             文档摄入流水线（Kafka 异步）
│   ├── retrieval/             向量检索封装
│   ├── config/                跨切配置（Kafka, VectorStore, Cors, ApiKey, ...）
│   └── common/                统一异常处理 + ErrorResponse
├── src/main/resources/
│   ├── application.yml        共用配置
│   ├── application-dev.yml    开发 profile 覆盖
│   ├── application-prod.yml   生产 profile 覆盖
│   ├── logback-spring.xml     dev 彩色控制台 / prod JSON
│   └── db/migration/          Flyway V1-V8
├── src/test/java/             单元测试 + 集成测试
├── docs/
│   ├── architecture/          系统架构 + 技术设计
│   └── superpowers/           历史规划与设计稿
├── docker-compose.yml         本地 PG + Kafka
├── Dockerfile
├── mvnw / mvnw.cmd            Maven Wrapper
└── pom.xml
```

---

## 数据库 Migration

Flyway 启动时自动执行 `src/main/resources/db/migration/V*.sql`。
当前到 V8：

| Version | 作用 |
|---|---|
| V1 | 初始 schema (knowledge_base, document, conversation, document_chunk) |
| V2 | document_chunk HNSW 索引（已废弃但保留迁移记录） |
| V3 | embedding 维度 1536 → 1024（百炼 text-embedding-v3） |
| V4 | 引入 Spring AI 标准 `vector_store` 表，废弃 document_chunk |
| V5 | conversation 加 `version BIGINT` 列，启用乐观锁 |
| V6 | 新增摄入事务 Outbox，并约束同一文档的 chunk 序号唯一 |
| V7 | 为向量增加文档外键与级联删除，阻止孤儿向量 |
| V8 | 清理未完成文档的旧随机 ID 部分向量，保证升级后可重试 |

上传事务同时写入 `document` 与 `ingestion_outbox`。后台发布器使用
`FOR UPDATE SKIP LOCKED` 批量锁定待发布事件，收到 Kafka 确认后标记为
`PUBLISHED`；失败时记录原因并指数退避重试。Kafka 至少一次投递产生的重复
消息由消费端幂等处理：已完成文档直接跳过；处理事务持有文档行锁，并用确定性
chunk ID 先完成 upsert、再裁剪旧尾部，向量写入与 `DONE` 状态原子提交。失败时
事务整体回滚，保留上一份完整向量集。V7 的 `vector_store.document_id` 外键保证
文档删除会级联清理向量，且已删除文档无法再写入孤儿向量。

---

## API 概览

| 模块 | 端点 |
|---|---|
| Knowledge Base | `GET/POST/PUT/DELETE /api/v1/knowledge-bases[/{id}]` |
| 语义检索 | `POST /api/v1/knowledge-bases/{kbId}/search` |
| Document | `POST/GET/DELETE /api/v1/knowledge-bases/{kbId}/documents[/{docId}]` |
| 网页摄取 | `POST /api/v1/knowledge-bases/{kbId}/documents/url` |
| Document Chunks | `GET /api/v1/knowledge-bases/{kbId}/documents/{docId}/chunks` |
| Chat (单轮 SSE) | `POST /api/v1/chat` |
| Conversation | `POST /api/v1/chat/conversations` |
| 多轮 SSE | `POST /api/v1/chat/conversations/{id}/messages` |
| Health | `GET /api/actuator/health{,/liveness,/readiness}` |

详见 Swagger UI 或 [`docs/architecture/TECHNICAL_DESIGN.md`](docs/architecture/TECHNICAL_DESIGN.md)。

网页摄取请求体为 `{ "url": "https://example.com/article" }`。接口创建异步摄取任务，
Kafka 消费端抓取单个网页并用 Spring AI `JsoupDocumentReader` 提取可见正文；不会递归跟踪页面中的链接。仅允许
公网 `http/https` 地址，每次重定向都会重新校验目标；响应必须是 HTML，正文上限为
5 MiB，连接和读取均设置超时。localhost、私网、链路本地、带凭据的 URL 会被拒绝。

Chat SSE 首帧为 `context`（本次实际送入 LLM 的 `SearchResultItem[]`），随后是
`token`，并以 `done` 或结构化 `error` 结束。客户端无需在流结束后再次调用
`/search`，避免查询重写或检索波动导致展示上下文与回答依据不一致。

---

## 常见运维操作

### 看摄入失败

```bash
# 状态分布
docker exec rag0429-postgres-1 psql -U rag -d rag -c \
  "SELECT status, COUNT(*) FROM document GROUP BY status;"

# 最近的 FAILED 错误
docker exec rag0429-postgres-1 psql -U rag -d rag -c \
  "SELECT id, name, error_message FROM document WHERE status='FAILED' ORDER BY updated_at DESC LIMIT 5;"
```

### 清理孤儿向量

```sql
DELETE FROM vector_store v
 USING (
   SELECT v.id FROM vector_store v
   LEFT JOIN document d ON d.id::text = v.metadata->>'document_id'
   WHERE d.id IS NULL
 ) orphan
 WHERE orphan.id = v.id;
```

### 重新摄入

```bash
DELETE /api/v1/knowledge-bases/{kbId}/documents/{docId}   # 旧记录
POST   /api/v1/knowledge-bases/{kbId}/documents (multipart) # 重传
```

更多排障见 `docs/architecture/TECHNICAL_DESIGN.md` § 13。

---

## Contributing

- 修代码先跑 `./mvnw test`
- 改 schema 必须新增 `V<N>__<desc>.sql`，**不改已有 migration**
- DTO 用 record，校验注解写在字段上（参考 `ChatRequest`）
- 异常用 `ResourceNotFoundException` / `IllegalArgumentException`，
  由 `GlobalExceptionHandler` 统一映射为结构化 `ErrorResponse`
- 提交信息遵循 Conventional Commits (`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `build:` / `chore:`)

---

## License

Internal / proprietary. Not for redistribution.
