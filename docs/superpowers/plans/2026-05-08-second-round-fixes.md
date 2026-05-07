# 第二轮优化计划

**Date:** 2026-05-08
**Source:** 上线前修复计划末尾"不在本次范围"列表
**Goal:** 把上一轮显式推迟的 6 项做完。每项独立可上线。

> 顺序按"小→大、低风险→高风险"。每项内部都有验证，每项独立 commit。

---

## A. topK `@Max` 上限（最小修复）

**问题**: `ChatRequest.topK / ConversationMessageRequest.topK / SearchKnowledgeBaseRequest.topK` 都没有上限。客户端传 1 亿就会向 pgvector 拉 1 亿行 → OOM + token 爆炸。

**方案**:
- 新增常量 `RetrievalLimits.MAX_TOP_K = 50`
- 三个 DTO 的 topK 字段加 `@Min(1) @Max(50)`
- 兜底：`RetrievalService.search` 内对 `topK` 做 `Math.min(topK, MAX_TOP_K)`

**验证**: 单元测试覆盖三个 DTO 的边界；手测 topK=1000 → 400 + fieldErrors。

---

## B. ModelRouter 死代码 + embeddingModel 字段语义决断

**问题**: `ModelRouter` 注入所有 EmbeddingModel bean 但没有调用方；`KnowledgeBase.embeddingModel` 字段让用户以为可以指定，但 `VectorStore` 是全局唯一的 EmbeddingModel，字段值实际不影响任何行为。

**方案**: 选 C —— 删 ModelRouter，保留 `embeddingModel` 字段但作为"信息字段"（记录这个 KB 在何种模型下被嵌入），文档化语义。
- 删除 `retrieval/ModelRouter.java`
- `KnowledgeBaseResponse` 字段保留
- 在 `KnowledgeBase.embeddingModel` 字段加 javadoc 说明：服务端不强制按此路由，仅做记录用途。
- DTO 校验：限制为 `application.yml` 配置的全局 model 名，避免误导 UI 选择。

**验证**: 编译通过；测试通过；KB CRUD 行为不变。

---

## C. 删除链路重构（DB cascade + 文件清理）

**问题**:
1. V1 schema 已有 `ON DELETE CASCADE` 但 Java 代码三处手动级联删
2. 删除 KB / Document 时**不删磁盘文件** → 文件永远泄漏
3. `DocumentController.delete` 不在事务里且不校验 `kbId`

**方案**:
- 删除 `KnowledgeBaseService.delete()` 中手动调用 `documentRepository.deleteByKnowledgeBaseId(id)` —— 依靠 DB cascade
- 删除 `DocumentRepository.deleteByKnowledgeBaseId` 方法（不再需要）
- 在删除前先查文件路径，删后调 `storageService.delete(filePath)` 清理磁盘
- 同步处理 vector_store：用 `ChunkQueryService.deleteByKnowledgeBase/deleteByDocument` 显式清理（vector_store 没有 FK，DB 不会自动级联）
- `DocumentController.delete` 移到新的 `DocumentService` 里（见 Workflow D）+ 校验 `kbId`

**验证**: 删除 KB 后磁盘 + DB + vector_store 三处都干净。

---

## D. 抽 DocumentService

**问题**: `DocumentController` 直接持有 Repository、做事务边界，不一致于 `KnowledgeBaseController` 的薄壳子模式。`/{docId}/chunks` 之前直接返回 JPA 实体（已在上一轮修），但删除逻辑还在 controller 里。

**方案**:
- 新建 `knowledge/DocumentService.java`：封装 list / delete / chunks
- `DocumentController` 只做 HTTP 转发，不持有 Repository
- `delete` 校验 `doc.knowledgeBase.id == kbId`，删除 vector_store + 文件 + DB row（C 的实现位置）

**验证**: 删除非本 KB 的 Document → 404；未授权场景外露最少。

---

## E. Conversation 并发竞态 + 历史窗口

**问题**:
1. 没有 `@Version`，并发写同一 conversation 时 last-writer-wins
2. 历史无上限，每次重写整 JSONB 列，长对话 O(n²) token 成本

**方案**:
- V5 Flyway migration: `ALTER TABLE conversation ADD COLUMN version BIGINT NOT NULL DEFAULT 0`
- `Conversation` 加 `@Version Long version`
- 配置 `app.chat.max-history-messages` 默认 20
- `ChatService.continueConversation` 持久化前对历史长度做截断（保留最近 N-1 条 + 当前用户消息）
- `ConversationPersistenceService.appendAssistantMessage` 也做截断
- 处理 `OptimisticLockException` → 转化为 `409 CONFLICT`（GlobalExceptionHandler 已有的 DataIntegrityViolation 不覆盖这个，需要单独 handler）

**验证**:
- 并发测试: 两次同时调用 → 一次成功一次 409
- 长对话: 上传 30 条 → 只保留最近 20

---

## F. IngestionConsumer 集成测试 (testcontainers)

**问题**: `IngestionConsumer.consume` 是最关键也最复杂的路径，但**完全没有测试**（之前的 unit test 只测 `DocumentParserFactory`）。修复了 saveFailureStatus self-invocation 后，没有自动测试覆盖确保它不退化。

**方案**:
- 添加 testcontainers 依赖（pg + kafka 模块 + spring-boot-testcontainers）
- 新建 `IngestionConsumerIntegrationTest.java`，使用 `@SpringBootTest` + `@Testcontainers`：
  - happy path: KB → upload → 等 PROCESSING → 等 DONE
  - failure path: 制造一个不可解析的"PDF"（实际是空文件） → 等 FAILED + errorMessage
  - 验证 vector_store 行数 = chunk_count
- 用 mock LLM endpoint（WireMock）拦截 OpenAI 兼容请求，返回固定 embedding 向量

**验证**: `mvn -pl . -Dtest=IngestionConsumerIntegrationTest test` 通过；CI 时间 < 2 min。

---

## 执行顺序

1. A → 单文件常量 + 三处加注解（最小）
2. B → 删 ModelRouter（清理）
3. C + D → 一起做（删除链路重构在 DocumentService 里实现）
4. E → V5 schema + 锁 + 窗口
5. F → 集成测试（最大，最后做）

每步完成后跑 `mvn test`，commit，进下一步。
