# 知识库 Admin 管理平台设计文档

**日期**：2026-05-07
**状态**：已审批，待实现

---

## 1. 概述

为现有 Spring Boot RAG 系统构建一个独立的前端 Admin 管理平台，供内部人员管理知识库、文档及测试 RAG 对话效果。

**目标**：提供可视化操作界面，替代直接调用 Swagger/API 的操作方式。

---

## 2. 技术栈

| 层 | 技术 |
|---|---|
| 框架 | React 18 + Vite |
| UI 组件库 | Ant Design 5 |
| 路由 | React Router v6 |
| 服务端状态 | TanStack Query (React Query v5) |
| HTTP 客户端 | 原生 fetch（封装为 `src/api/` 模块） |
| SSE 流式 | `fetch + ReadableStream` |
| 语言 | TypeScript |
| 部署 | 独立静态站（与 Spring Boot 前后端分离） |
| 开发代理 | Vite proxy：`/api → http://localhost:8080` |

无额外状态管理库（Zustand/Redux）。跨模块共享的少量客户端状态（当前选中 KB）用 React Context 传递。

---

## 3. 项目结构

```
admin/                              # Vite 项目根目录（与 Spring Boot 项目平级）
├── index.html
├── vite.config.ts                  # proxy /api → http://localhost:8080
├── tsconfig.json
├── package.json
└── src/
    ├── main.tsx                    # 入口，BrowserRouter + QueryClientProvider
    ├── App.tsx                     # 路由表
    ├── api/                        # 后端调用层
    │   ├── client.ts               # 基础 fetch 封装（统一错误处理）
    │   ├── knowledge-base.ts       # KB CRUD API 函数
    │   ├── document.ts             # 文档上传 / 列表 / 删除 API 函数
    │   └── chat.ts                 # 对话 / SSE 流式 API 函数
    ├── layouts/
    │   └── AppLayout.tsx           # 侧边栏 + 顶栏 + Outlet
    ├── pages/
    │   ├── knowledge-base/
    │   │   ├── KbListPage.tsx      # 知识库列表主页
    │   │   └── KbFormModal.tsx     # 新建/编辑 Modal
    │   ├── document/
    │   │   ├── DocListPage.tsx     # 文档列表主页（含上传区）
    │   │   ├── ChunkDrawer.tsx     # 查看 Chunk 内容抽屉
    │   │   └── ErrorModal.tsx      # 查看摄入错误 Modal
    │   └── chat/
    │       ├── ChatPage.tsx        # 对话主页
    │       └── ChunkContextDrawer.tsx  # 查看召回 Chunks 抽屉
    ├── hooks/
    │   ├── useKbList.ts            # 封装 React Query useQuery
    │   ├── useDocList.ts           # 含自动轮询逻辑
    │   └── useStreamingChat.ts     # 管理 SSE 流式输出状态
    └── types/
        └── api.ts                  # 所有后端响应类型定义
```

---

## 4. 路由设计

```
/                          → redirect → /knowledge-bases
/knowledge-bases           → KbListPage（知识库列表）
/knowledge-bases/:kbId/documents  → DocListPage（文档管理）
/chat                      → ChatPage（Chat 测试台）
```

所有路由套在 `AppLayout`（侧边栏 + 顶栏）内渲染。

**"文档管理"侧边栏导航说明**：侧边栏"文档管理"菜单项点击后跳转到 `/knowledge-bases`（知识库列表），并通过 URL state 滚动定位到列表，提示用户点击某知识库行的"文档"按钮进入文档管理。文档管理页不单独作为顶级入口，因为必须依托 kbId 上下文。

---

## 5. 布局设计

**AppLayout** — 固定侧边栏方案：

| 区域 | 规格 |
|---|---|
| 侧边栏宽度 | 220px，深色主题（`#001529`） |
| Logo 区 | 蓝色方形图标 + "RAG Admin" 文字 |
| 导航项 | 知识库管理 / 文档管理 / Chat 测试台，图标 + 文字 |
| 顶栏高度 | 48px，面包屑 + 右侧后端健康状态指示器 |
| 内容区背景 | `#f5f5f5` |

**健康状态指示器**：每 30 秒 `GET /actuator/health`，在线显示绿色"● 后端在线"，离线显示红色警告。

---

## 6. 模块设计

### 6.1 知识库管理（`/knowledge-bases`）

**列表页**

- 顶部统计卡片：知识库总数（来自分页 `totalElements`）；文档总数、Chunk 总数通过 `GET /api/v1/knowledge-bases/:id` 遍历或后端新增统计端点获取（实现阶段根据代价决定是否保留这两项）
- Ant Design Table，列：名称+描述、Embedding 模型、Chunk Size/Overlap、状态徽章、创建时间、操作列
- 操作列：**文档**（跳转到 `/knowledge-bases/:kbId/documents`）、**编辑**、**删除**
- 表格顶部搜索框（前端过滤名称）
- "新建知识库"按钮 → 打开 `KbFormModal`

**新建/编辑 Modal（`KbFormModal`）**

| 字段 | 组件 | 校验 |
|---|---|---|
| 名称 | Input | 必填 |
| 描述 | TextArea | 可选 |
| Embedding 模型 | Select（固定选项：`text-embedding-v3`） | 必填 |
| Chunk Size | InputNumber（步长 64） | 必填，> 0 |
| Chunk Overlap | InputNumber（步长 16） | 必填，>= 0，< Chunk Size |

成功后调用 `queryClient.invalidateQueries(['kb-list'])` 刷新列表。

**删除**：Popconfirm 二次确认，删除时后端级联删除文档和 Chunks（由后端处理）。

**API 对应**：

```
GET    /api/v1/knowledge-bases          列表（分页，pageSize=50）
POST   /api/v1/knowledge-bases          新建
PUT    /api/v1/knowledge-bases/:id      编辑
DELETE /api/v1/knowledge-bases/:id      删除
```

---

### 6.2 文档管理（`/knowledge-bases/:kbId/documents`）

**页面布局**：顶部上传区 + 下方文档列表。

**上传区（Ant Design Dragger）**

- 拖拽或点击选择文件，支持 PDF / DOCX / TXT / MD
- 调用 `POST /api/v1/knowledge-bases/:kbId/documents`（multipart/form-data）
- 上传成功后将新文档追加到列表（状态 `PENDING`），立即启动轮询

**文档列表**

列：文件名、类型徽章（颜色区分）、状态徽章、Chunk 数、上传时间、操作列。

| 状态 | 样式 |
|---|---|
| PENDING | 灰色，等待中 |
| PROCESSING | 橙色 + 动态圆点，行背景 `#fffbe6`，启动轮询 |
| DONE | 绿色 |
| FAILED | 红色，操作列显示"查看错误" |

**自动轮询逻辑（`useDocList` hook）**：

```typescript
const hasActive = docs.some(d => d.status === 'PENDING' || d.status === 'PROCESSING');
// refetchInterval: hasActive ? 3000 : false
```

列表中无进行中文档时停止轮询，避免无效请求。

**操作列**：
- DONE → "查看 Chunks"（打开 `ChunkDrawer`）、"删除"
- FAILED → "查看错误"（打开 `ErrorModal` 展示 `errorMessage`）、"删除"
- PROCESSING → 禁用所有操作

**ChunkDrawer**：右侧 Drawer，分页展示该文档所有 Chunks（`chunk_index` 排序），每条显示内容全文。

**API 对应**：

```
GET    /api/v1/knowledge-bases/:kbId/documents          文档列表（分页）
POST   /api/v1/knowledge-bases/:kbId/documents          上传文档
DELETE /api/v1/knowledge-bases/:kbId/documents/:docId   删除文档
GET    /api/v1/knowledge-bases/:kbId/documents/:docId/chunks  Chunk 列表
```

---

### 6.3 Chat 测试台（`/chat`）

**布局**：顶栏（KB 选择器 + 清空按钮）+ 消息流 + 底部输入区。

**KB 选择器**：顶栏 Select，切换 KB 时：
1. 重置消息列表
2. 若为多轮模式，调 `POST /api/v1/chat/conversations` 创建新 Conversation，保存 `conversationId`

**消息气泡**：
- 用户消息：蓝色背景，右对齐
- AI 消息：白色卡片，左对齐，带机器人头像
- 流式输出时末尾显示闪烁光标，输出完成后消失

**SSE 流式实现（`useStreamingChat` hook）**：

```typescript
// 多轮模式
POST /api/v1/chat/conversations/:id/messages  → EventSource/ReadableStream
// 单次模式
POST /api/v1/chat  → EventSource/ReadableStream
```

使用 `fetch + ReadableStream`（非 `EventSource`，原因：需要 POST body）：
1. 创建占位 AI 消息（content 为空）
2. 逐 chunk 读取 SSE token，追加到消息 content
3. `done` 时标记消息完成，移除光标

**底部输入区**：
- topK 数字调节器（默认 5，范围 1–20）
- 多轮/单次模式切换（Segmented 组件）
- Textarea（`Enter` 发送，`Shift+Enter` 换行）+ 发送按钮
- 发送中禁用输入框和按钮

**召回 Chunks**：每条 AI 回复下方"📎 查看召回 Chunks"按钮 → `ChunkContextDrawer`。由于后端 SSE 流只返回文本 token、不含 chunk 元数据，实现方式为：流式输出完成后，前端用相同问题和 topK 参数补调一次 `POST /api/v1/knowledge-bases/:kbId/search`，将结果缓存并在 Drawer 中展示。搜索结果仅作参考，与实际 RAG 检索时序可能有微小差异。

**清空对话**：仅清空前端 `messages` 状态，不调删除接口。

**API 对应**：

```
POST /api/v1/chat                                    单次问答（SSE）
POST /api/v1/chat/conversations                      创建会话
POST /api/v1/chat/conversations/:id/messages         多轮对话（SSE）
```

---

## 7. API 层设计

`src/api/client.ts` 统一封装：

```typescript
// 非流式请求：非 2xx 时抛出包含 message 的 Error
async function request<T>(path: string, init?: RequestInit): Promise<T>

// 流式请求：返回 ReadableStream<string>（已解析 SSE data 字段）
async function streamRequest(path: string, body: unknown): Promise<ReadableStream<string>>
```

所有错误通过 Ant Design `message.error()` 展示给用户（在 React Query 的 `onError` 回调中统一处理）。

---

## 8. 跨域与代理

**开发环境**：Vite `server.proxy` 将 `/api` 转发到 `http://localhost:8080`，无跨域问题。

**生产环境**：通过 Nginx 反向代理，前端静态文件和后端 API 同域：
```nginx
location /api/  { proxy_pass http://spring-boot:8080/api/; }
location /      { root /usr/share/nginx/html; try_files $uri /index.html; }
```

同时在 Spring Boot 添加 `@CrossOrigin` 或全局 CORS 配置，支持本地开发时直连。

---

## 9. 错误处理

| 场景 | 处理方式 |
|---|---|
| API 4xx/5xx | `message.error(errorMessage)` 弹出提示 |
| 网络断开 | SSE 流中断时显示"连接中断，请重试" |
| 上传失败 | Upload 组件内联显示错误状态 |
| 文档 FAILED | 列表行红色高亮，操作列"查看错误"弹出详情 |
| 后端离线 | 顶栏健康指示器变红，不阻断页面渲染 |

---

## 10. 不在范围内

- 用户登录 / 鉴权
- 系统监控（Kafka 消费延迟、队列统计）
- 多用户 / 权限管理
- 移动端适配
