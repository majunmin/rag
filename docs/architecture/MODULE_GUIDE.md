# 后端模块开发指南

本文说明 `rag0429` 的 Maven 模块边界、依赖方向和代码放置规则。目标是让
新增功能时能快速判断“代码应该放在哪里”，并在编译阶段阻止循环依赖。

## 1. 架构形式

项目采用**模块化单体**：开发和部署时仍然是一个 Spring Boot 应用，但业务代码
拆成多个 Maven JAR。相比直接拆微服务，这种方式保留了本地事务和简单部署；相比
单一 `src` 目录，又能通过模块依赖限制跨域调用。

## 2. 模块职责

| 模块 | 负责 | 不负责 | 业务依赖 |
|---|---|---|---|
| `rag-common` | 错误响应、通用异常 | 业务实体、业务服务 | 无 |
| `rag-knowledge` | 知识库/文档领域、CRUD、持久化、存储端口 | 文件解析、Kafka、检索 | `rag-common` |
| `rag-ingestion` | 上传、网页抓取、解析切分、Outbox、Kafka、存储实现 | 对话、检索策略 | `rag-knowledge` |
| `rag-retrieval` | 向量召回、查询改写、重排、搜索 API | 知识库持久化、对话状态 | 无 |
| `rag-chat` | 对话用例、会话持久化、SSE 协议 | 向量检索实现 | `rag-common`、`rag-retrieval` |
| `rag-app` | 应用启动、共享 Bean、配置文件、Flyway、集成测试 | 业务规则 | 全部业务模块 |

依赖方向：

```text
rag-app ─┬─> rag-chat ─> rag-retrieval
         ├─> rag-ingestion ─> rag-knowledge ─> rag-common
         ├─> rag-retrieval
         └─> rag-knowledge
```

不得增加反向依赖。例如 `rag-knowledge` 不得依赖 `rag-ingestion`。需要调用下层
技术能力时，由业务所有者定义端口，再由基础设施模块实现。当前
`knowledge.application.port.StorageService` 就是这种做法。

## 3. 包结构

每个业务模块按以下规则分包：

```text
com.majm.rag.<module>/
├── api/                    Controller、对外 DTO、协议适配
├── application/            用例编排、事务边界
│   └── port/               调用外部能力所需的接口
├── domain/                 实体、值对象、领域状态
├── infrastructure/         数据库、消息、网络、文件系统等实现
│   ├── persistence/
│   ├── messaging/
│   ├── storage/
│   └── document/
└── config/                 仅本模块使用的 Spring 配置
```

并非每个模块都必须创建所有目录。只有存在对应职责时才增加包，避免空层和无意义
接口。跨模块共享配置放在 `rag-app/config`，业务模块不能把应用组装逻辑藏在内部。

## 4. 代码放置示例

| 需求 | 放置位置 |
|---|---|
| 新增知识库字段和数据库查询 | `rag-knowledge/domain`、`infrastructure/persistence` |
| 新增文件格式解析器 | `rag-ingestion/infrastructure/document` |
| 新增 Kafka 摄取事件 | `rag-ingestion/domain`、`infrastructure/messaging` |
| 新增召回或重排策略 | `rag-retrieval/application` 或 `rerank` |
| 修改 SSE 事件协议 | `rag-chat/api` |
| 新增全局 Bean 或运行配置 | `rag-app/config`、`rag-app/src/main/resources` |
| 新增跨模块错误码 | `rag-common/api` |

## 5. 构建与验证

```bash
# 编译全部模块
./mvnw compile

# 测试全部模块
./mvnw test

# 只构建可执行应用及其依赖
./mvnw -pl rag-app -am package

# 本地启动
./mvnw -pl rag-app -am spring-boot:run
```

最终可执行 JAR 位于 `rag-app/target/rag-app-0.0.1-SNAPSHOT.jar`。其余模块生成的
JAR 是内部库，不应单独运行。

## 6. 变更检查清单

1. 新代码是否放在拥有该业务规则的模块，而不是方便引用的模块。
2. 是否保持依赖箭头单向，没有让下层模块导入上层模块。
3. Controller 是否只做协议转换，把业务编排交给 `application`。
4. 外部技术能力是否通过端口隔离，接口是否由调用方模块定义。
5. 单元测试是否跟随被测模块，跨模块流程测试是否放在 `rag-app`。
6. 配置、Flyway 和可执行打包是否仍由 `rag-app` 统一管理。
