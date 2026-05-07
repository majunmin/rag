# Admin 管理平台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 React + Vite + Ant Design 5 前端 Admin 平台，覆盖知识库管理、文档管理、Chat 测试台三个模块，与现有 Spring Boot 后端前后端分离部署。

**Architecture:** `admin/` 目录与 `src/` 平级，独立 Vite 项目。开发时通过 Vite proxy 将 `/api` 转发到 `localhost:8080` 解决跨域；后端新增 `CorsConfig` 支持生产直连。React Router v6 管理路由，TanStack Query 管理服务端状态（含文档状态自动轮询），`fetch + ReadableStream` 处理 SSE 流式输出。

**Tech Stack:** React 18, Vite 5, TypeScript 5, Ant Design 5, React Router v6, TanStack Query v5, Vitest

---

## 文件清单

### 后端（修改/新增）

| 操作 | 文件 |
|---|---|
| 新增 | `src/main/java/com/majm/rag/config/CorsConfig.java` |

### 前端（全部新增，位于 `admin/`）

| 文件 | 职责 |
|---|---|
| `package.json` | 依赖声明 |
| `vite.config.ts` | Vite 配置，含 `/api` proxy |
| `tsconfig.json` | TypeScript 配置 |
| `index.html` | HTML 入口 |
| `src/main.tsx` | React 入口，挂载 QueryClientProvider + BrowserRouter |
| `src/App.tsx` | 路由表 |
| `src/types/api.ts` | 后端响应类型定义 |
| `src/api/client.ts` | fetch 基础封装（request + streamRequest） |
| `src/api/knowledge-base.ts` | KB CRUD 函数 |
| `src/api/document.ts` | 文档上传/列表/删除/chunk 查询 |
| `src/api/chat.ts` | 对话创建、SSE 发起 |
| `src/layouts/AppLayout.tsx` | 侧边栏 + 顶栏 + Outlet |
| `src/pages/knowledge-base/KbListPage.tsx` | 知识库列表（Table + 统计卡片） |
| `src/pages/knowledge-base/KbFormModal.tsx` | 新建/编辑 Modal |
| `src/pages/document/DocListPage.tsx` | 文档列表 + 上传区 |
| `src/pages/document/ChunkDrawer.tsx` | Chunk 内容抽屉 |
| `src/pages/document/ErrorModal.tsx` | 摄入错误详情 Modal |
| `src/pages/chat/ChatPage.tsx` | Chat 测试台主页 |
| `src/pages/chat/ChunkContextDrawer.tsx` | 召回 Chunks 抽屉 |
| `src/hooks/useKbList.ts` | KB 列表 React Query hook |
| `src/hooks/useDocList.ts` | 文档列表 hook（含轮询逻辑） |
| `src/hooks/useStreamingChat.ts` | SSE 流式对话状态管理 |
| `src/test/api.client.test.ts` | API client 单元测试（Vitest） |

---

## Task 1: 后端 CORS 配置

**Files:**
- Create: `src/main/java/com/majm/rag/config/CorsConfig.java`

- [ ] **Step 1: 创建 CorsConfig**

```java
// src/main/java/com/majm/rag/config/CorsConfig.java
package com.majm.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("http://localhost:*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS，无错误输出。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/majm/rag/config/CorsConfig.java
git commit -m "feat: add CORS config for admin frontend dev (localhost:*)"
```

---

## Task 2: 前端项目脚手架

**Files:**
- Create: `admin/package.json`, `admin/vite.config.ts`, `admin/tsconfig.json`, `admin/index.html`
- Create: `admin/src/main.tsx`, `admin/src/App.tsx`

> 前提：Node 20+ 已安装（`node -v` 确认）。

- [ ] **Step 1: 在项目根目录创建 admin/ 并初始化**

```bash
cd /Users/majunmin/workspace/learn/rag0429
npm create vite@latest admin -- --template react-ts
cd admin
```

Expected: 生成 `admin/` 目录，含 `src/App.tsx`, `src/main.tsx` 等基础文件。

- [ ] **Step 2: 安装依赖**

```bash
npm install antd @ant-design/icons react-router-dom @tanstack/react-query
npm install -D vitest @vitest/ui jsdom @testing-library/react @testing-library/jest-dom
```

Expected: `package.json` 中出现以上依赖。

- [ ] **Step 3: 覆盖 vite.config.ts**

```typescript
// admin/vite.config.ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
```

- [ ] **Step 4: 创建测试 setup 文件**

```typescript
// admin/src/test/setup.ts
import '@testing-library/jest-dom'
```

- [ ] **Step 5: 覆盖 tsconfig.json（添加路径别名支持）**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"]
}
```

- [ ] **Step 6: 覆盖 index.html**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>RAG Admin</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 7: 覆盖 src/main.tsx**

```tsx
// admin/src/main.tsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import 'antd/dist/reset.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 10_000 },
    mutations: { retry: 0 },
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
)
```

- [ ] **Step 8: 覆盖 src/App.tsx（占位路由，后续各 Task 补充）**

```tsx
// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<div>知识库管理（占位）</div>} />
        <Route path="knowledge-bases/:kbId/documents" element={<div>文档管理（占位）</div>} />
        <Route path="chat" element={<div>Chat 测试台（占位）</div>} />
      </Route>
    </Routes>
  )
}
```

- [ ] **Step 9: 验证项目启动（后端可以不运行）**

```bash
# 在 admin/ 目录下
npm run dev
```

Expected: 浏览器打开 `http://localhost:5173` 显示页面不报错（内容为占位文字即可）。

- [ ] **Step 10: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/
git commit -m "feat(admin): scaffold React+Vite+AntD5 project with proxy config"
```

---

## Task 3: TypeScript 类型 + API 客户端层

**Files:**
- Create: `admin/src/types/api.ts`
- Create: `admin/src/api/client.ts`
- Create: `admin/src/api/knowledge-base.ts`
- Create: `admin/src/api/document.ts`
- Create: `admin/src/api/chat.ts`
- Create: `admin/src/test/api.client.test.ts`

- [ ] **Step 1: 写类型定义**

```typescript
// admin/src/types/api.ts

export type KbStatus = 'ACTIVE' | 'ARCHIVED'
export type DocStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  embeddingModel: string
  chunkSize: number
  chunkOverlap: number
  status: KbStatus
  createdAt: string
}

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface KbDocument {
  id: string
  name: string
  fileType: string
  status: DocStatus
  chunkCount: number
  createdAt: string
}

export interface UploadDocumentResponse {
  documentId: string
  name: string
  status: string
}

export interface DocumentChunk {
  id: string
  content: string
  chunkIndex: number
  metadata: Record<string, unknown>
  createdAt: string
}

export interface SearchResultItem {
  content: string
  metadata: Record<string, unknown>
}

export interface Conversation {
  id: string
  knowledgeBaseId: string
  messages: Array<{ role: string; content: string }>
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  chunks?: SearchResultItem[]
}

export interface CreateKbRequest {
  name: string
  description?: string
  embeddingModel: string
  chunkSize: number
  chunkOverlap: number
}

export interface UpdateKbRequest {
  name?: string
  description?: string
  chunkSize?: number
  chunkOverlap?: number
}
```

- [ ] **Step 2: 写 API client 基础封装**

```typescript
// admin/src/api/client.ts

const BASE = '/api/v1'

export async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const headers: Record<string, string> = {
    ...(init?.body && !(init.body instanceof FormData)
      ? { 'Content-Type': 'application/json' }
      : {}),
    ...(init?.headers as Record<string, string>),
  }
  const res = await fetch(`${BASE}${path}`, { ...init, headers })
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(text || `HTTP ${res.status}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export async function streamRequest(
  path: string,
  body: unknown,
): Promise<ReadableStream<string>> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(text || `HTTP ${res.status}`)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  return new ReadableStream<string>({
    async pull(controller) {
      const { done, value } = await reader.read()
      if (done) {
        controller.close()
        return
      }
      const text = decoder.decode(value, { stream: true })
      for (const line of text.split('\n')) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const token = trimmed.slice(5).trim()
          if (token && token !== '[DONE]') controller.enqueue(token)
        }
      }
    },
    cancel() {
      reader.cancel()
    },
  })
}
```

- [ ] **Step 3: 写 API client 单元测试**

```typescript
// admin/src/test/api.client.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { request, streamRequest } from '../api/client'

const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

beforeEach(() => mockFetch.mockReset())

describe('request', () => {
  it('returns parsed JSON on 200', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ id: '1', name: 'KB' }),
    })
    const result = await request('/knowledge-bases/1')
    expect(result).toEqual({ id: '1', name: 'KB' })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/knowledge-bases/1',
      expect.objectContaining({}),
    )
  })

  it('returns undefined on 204', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, status: 204 })
    const result = await request('/knowledge-bases/1', { method: 'DELETE' })
    expect(result).toBeUndefined()
  })

  it('throws Error with server message on 4xx', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      text: async () => 'KnowledgeBase not found: abc',
    })
    await expect(request('/knowledge-bases/abc')).rejects.toThrow(
      'KnowledgeBase not found: abc',
    )
  })
})

describe('streamRequest', () => {
  it('throws on non-ok response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      body: null,
      text: async () => 'Bad Request',
    })
    await expect(streamRequest('/chat', {})).rejects.toThrow('Bad Request')
  })
})
```

- [ ] **Step 4: 运行测试确认通过**

```bash
# 在 admin/ 目录下
npm run test -- --run src/test/api.client.test.ts
```

Expected:
```
✓ request > returns parsed JSON on 200
✓ request > returns undefined on 204
✓ request > throws Error with server message on 4xx
✓ streamRequest > throws on non-ok response
Test Files  1 passed (1)
```

- [ ] **Step 5: 写知识库 API 函数**

```typescript
// admin/src/api/knowledge-base.ts
import { request } from './client'
import type {
  KnowledgeBase,
  PageResult,
  CreateKbRequest,
  UpdateKbRequest,
} from '../types/api'

export const listKbs = (page = 0, size = 50) =>
  request<PageResult<KnowledgeBase>>(
    `/knowledge-bases?page=${page}&size=${size}`,
  )

export const getKb = (id: string) =>
  request<KnowledgeBase>(`/knowledge-bases/${id}`)

export const createKb = (body: CreateKbRequest) =>
  request<KnowledgeBase>('/knowledge-bases', {
    method: 'POST',
    body: JSON.stringify(body),
  })

export const updateKb = (id: string, body: UpdateKbRequest) =>
  request<KnowledgeBase>(`/knowledge-bases/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })

export const deleteKb = (id: string) =>
  request<void>(`/knowledge-bases/${id}`, { method: 'DELETE' })
```

- [ ] **Step 6: 写文档 API 函数**

```typescript
// admin/src/api/document.ts
import { request } from './client'
import type {
  KbDocument,
  PageResult,
  UploadDocumentResponse,
  DocumentChunk,
  SearchResultItem,
} from '../types/api'

export const listDocuments = (kbId: string, page = 0, size = 50) =>
  request<PageResult<KbDocument>>(
    `/knowledge-bases/${kbId}/documents?page=${page}&size=${size}`,
  )

export const uploadDocument = (kbId: string, file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request<UploadDocumentResponse>(
    `/knowledge-bases/${kbId}/documents`,
    { method: 'POST', body: form },
  )
}

export const deleteDocument = (kbId: string, docId: string) =>
  request<void>(`/knowledge-bases/${kbId}/documents/${docId}`, {
    method: 'DELETE',
  })

export const listChunks = (kbId: string, docId: string) =>
  request<DocumentChunk[]>(
    `/knowledge-bases/${kbId}/documents/${docId}/chunks`,
  )

export const searchKb = (kbId: string, query: string, topK: number) =>
  request<SearchResultItem[]>(`/knowledge-bases/${kbId}/search`, {
    method: 'POST',
    body: JSON.stringify({ query, topK }),
  })
```

- [ ] **Step 7: 写对话 API 函数**

```typescript
// admin/src/api/chat.ts
import { request, streamRequest } from './client'
import type { Conversation } from '../types/api'

export const createConversation = (knowledgeBaseId: string) =>
  request<Conversation>('/chat/conversations', {
    method: 'POST',
    body: JSON.stringify({ knowledgeBaseId }),
  })

export const streamSingleChat = (
  knowledgeBaseId: string,
  question: string,
  topK: number,
) => streamRequest('/chat', { knowledgeBaseId, question, topK })

export const streamConversation = (
  conversationId: string,
  question: string,
  topK: number,
) =>
  streamRequest(`/chat/conversations/${conversationId}/messages`, {
    question,
    topK,
  })
```

- [ ] **Step 8: TypeScript 类型检查**

```bash
# 在 admin/ 目录下
npx tsc --noEmit
```

Expected: 无错误输出。

- [ ] **Step 9: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/src/types/ admin/src/api/ admin/src/test/
git commit -m "feat(admin): add TypeScript types and API client layer with tests"
```

---

## Task 4: AppLayout — 侧边栏 + 顶栏

**Files:**
- Create: `admin/src/layouts/AppLayout.tsx`

- [ ] **Step 1: 创建 AppLayout**

```tsx
// admin/src/layouts/AppLayout.tsx
import { useState, useEffect } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Breadcrumb, Badge, theme } from 'antd'
import {
  DatabaseOutlined,
  FileTextOutlined,
  MessageOutlined,
} from '@ant-design/icons'

const { Sider, Header, Content } = Layout

const NAV_ITEMS = [
  { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库管理' },
  { key: '/knowledge-bases', icon: <FileTextOutlined />, label: '文档管理' },
  { key: '/chat', icon: <MessageOutlined />, label: 'Chat 测试台' },
]

// Map full pathnames to breadcrumb label
function useBreadcrumb(pathname: string): string[] {
  if (pathname.startsWith('/knowledge-bases') && pathname.includes('/documents'))
    return ['知识库管理', '文档管理']
  if (pathname.startsWith('/knowledge-bases')) return ['知识库管理']
  if (pathname.startsWith('/chat')) return ['Chat 测试台']
  return []
}

// Resolve which nav key is "active"
function activeKey(pathname: string): string {
  if (pathname.startsWith('/chat')) return '/chat'
  return '/knowledge-bases'
}

export default function AppLayout() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [healthy, setHealthy] = useState<boolean | null>(null)
  const breadcrumbs = useBreadcrumb(pathname)

  useEffect(() => {
    const check = () =>
      fetch('/api/actuator/health')
        .then(r => setHealthy(r.ok))
        .catch(() => setHealthy(false))
    check()
    const timer = setInterval(check, 30_000)
    return () => clearInterval(timer)
  }, [])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark" style={{ position: 'fixed', height: '100vh', left: 0, top: 0 }}>
        {/* Logo */}
        <div
          style={{
            padding: '16px 20px',
            borderBottom: '1px solid rgba(255,255,255,0.08)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <div
            style={{
              width: 28, height: 28,
              background: '#1677ff',
              borderRadius: 6,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontWeight: 700, fontSize: 14, color: '#fff',
            }}
          >
            R
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 600, color: '#fff' }}>RAG Admin</div>
            <div style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)' }}>Knowledge Platform</div>
          </div>
        </div>

        {/* Navigation */}
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[activeKey(pathname)]}
          style={{ borderRight: 0, marginTop: 8 }}
          items={NAV_ITEMS.map(item => ({
            key: item.key,
            icon: item.icon,
            label: item.label,
          }))}
          onClick={({ key }) => navigate(key)}
        />

        {/* Footer: backend URL */}
        <div
          style={{
            position: 'absolute', bottom: 0, left: 0, right: 0,
            padding: '12px 20px',
            borderTop: '1px solid rgba(255,255,255,0.08)',
            color: 'rgba(255,255,255,0.3)',
            fontSize: 11,
          }}
        >
          API: localhost:8080
        </div>
      </Sider>

      <Layout style={{ marginLeft: 220 }}>
        {/* Header */}
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            height: 48,
            lineHeight: '48px',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky', top: 0, zIndex: 10,
          }}
        >
          <Breadcrumb
            items={[{ title: '首页' }, ...breadcrumbs.map(b => ({ title: b }))]}
            style={{ fontSize: 13 }}
          />
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            {healthy === null ? null : healthy ? (
              <Badge color="green" text={<span style={{ color: '#52c41a' }}>后端在线</span>} />
            ) : (
              <Badge color="red" text={<span style={{ color: '#ff4d4f' }}>后端离线</span>} />
            )}
          </div>
        </Header>

        {/* Page content */}
        <Content style={{ background: '#f5f5f5', minHeight: 'calc(100vh - 48px)', padding: 20 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
```

- [ ] **Step 2: 启动开发服务器，手动验证**

```bash
cd admin && npm run dev
```

打开 `http://localhost:5173`，验证：
- 深色侧边栏、Logo 区显示正常
- 点击导航项能跳转路由
- 顶栏面包屑随路由变化
- 后端离线时健康指示器显示红色（后端未启动时预期为红色）

- [ ] **Step 3: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/src/layouts/
git commit -m "feat(admin): add AppLayout with sidebar, breadcrumb, health indicator"
```

---

## Task 5: 知识库管理页

**Files:**
- Create: `admin/src/hooks/useKbList.ts`
- Create: `admin/src/pages/knowledge-base/KbFormModal.tsx`
- Create: `admin/src/pages/knowledge-base/KbListPage.tsx`
- Modify: `admin/src/App.tsx`

- [ ] **Step 1: 创建 useKbList hook**

```typescript
// admin/src/hooks/useKbList.ts
import { useQuery } from '@tanstack/react-query'
import { listKbs } from '../api/knowledge-base'

export const KB_LIST_KEY = ['kb-list'] as const

export function useKbList() {
  return useQuery({
    queryKey: KB_LIST_KEY,
    queryFn: () => listKbs(0, 50),
  })
}
```

- [ ] **Step 2: 创建 KbFormModal**

```tsx
// admin/src/pages/knowledge-base/KbFormModal.tsx
import { useEffect } from 'react'
import { Modal, Form, Input, Select, InputNumber, message } from 'antd'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createKb, updateKb } from '../../api/knowledge-base'
import { KB_LIST_KEY } from '../../hooks/useKbList'
import type { KnowledgeBase } from '../../types/api'

interface Props {
  open: boolean
  editing: KnowledgeBase | null
  onClose: () => void
}

export default function KbFormModal({ open, editing, onClose }: Props) {
  const [form] = Form.useForm()
  const qc = useQueryClient()

  useEffect(() => {
    if (open) {
      form.setFieldsValue(
        editing
          ? {
              name: editing.name,
              description: editing.description ?? '',
              embeddingModel: editing.embeddingModel,
              chunkSize: editing.chunkSize,
              chunkOverlap: editing.chunkOverlap,
            }
          : {
              embeddingModel: 'text-embedding-v3',
              chunkSize: 512,
              chunkOverlap: 64,
            },
      )
    } else {
      form.resetFields()
    }
  }, [open, editing, form])

  const mutation = useMutation({
    mutationFn: (values: Parameters<typeof createKb>[0]) =>
      editing ? updateKb(editing.id, values) : createKb(values),
    onSuccess: () => {
      message.success(editing ? '更新成功' : '创建成功')
      qc.invalidateQueries({ queryKey: KB_LIST_KEY })
      onClose()
    },
    onError: (e: Error) => message.error(e.message),
  })

  return (
    <Modal
      title={editing ? '编辑知识库' : '新建知识库'}
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={mutation.isPending}
      destroyOnClose
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={values => mutation.mutate(values)}
      >
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="例：产品手册 KB" />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} placeholder="可选" />
        </Form.Item>
        <Form.Item name="embeddingModel" label="Embedding 模型" rules={[{ required: true }]}>
          <Select options={[{ value: 'text-embedding-v3', label: 'text-embedding-v3' }]} />
        </Form.Item>
        <Form.Item name="chunkSize" label="Chunk Size" rules={[{ required: true }]}>
          <InputNumber min={64} max={4096} step={64} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="chunkOverlap"
          label="Chunk Overlap"
          rules={[
            { required: true },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (value >= 0 && value < getFieldValue('chunkSize'))
                  return Promise.resolve()
                return Promise.reject(new Error('Overlap 必须 ≥ 0 且 < Chunk Size'))
              },
            }),
          ]}
        >
          <InputNumber min={0} step={16} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
```

- [ ] **Step 3: 创建 KbListPage**

```tsx
// admin/src/pages/knowledge-base/KbListPage.tsx
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Button, Table, Tag, Popconfirm, Input, Space,
  Card, Statistic, Row, Col, message, Typography,
} from 'antd'
import {
  PlusOutlined, FolderOpenOutlined,
  EditOutlined, DeleteOutlined,
} from '@ant-design/icons'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { deleteKb } from '../../api/knowledge-base'
import { useKbList, KB_LIST_KEY } from '../../hooks/useKbList'
import KbFormModal from './KbFormModal'
import type { KnowledgeBase } from '../../types/api'

const { Title, Text } = Typography

export default function KbListPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data, isLoading } = useKbList()
  const [search, setSearch] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<KnowledgeBase | null>(null)

  const kbs = data?.content ?? []
  const filtered = kbs.filter(kb =>
    kb.name.toLowerCase().includes(search.toLowerCase()),
  )

  const deleteMutation = useMutation({
    mutationFn: deleteKb,
    onSuccess: () => {
      message.success('删除成功')
      qc.invalidateQueries({ queryKey: KB_LIST_KEY })
    },
    onError: (e: Error) => message.error(e.message),
  })

  const columns = [
    {
      title: '名称',
      key: 'name',
      render: (kb: KnowledgeBase) => (
        <div>
          <Text strong>{kb.name}</Text>
          {kb.description && (
            <div><Text type="secondary" style={{ fontSize: 12 }}>{kb.description}</Text></div>
          )}
        </div>
      ),
    },
    {
      title: 'Embedding 模型',
      dataIndex: 'embeddingModel',
      key: 'embeddingModel',
    },
    {
      title: 'Chunk 配置',
      key: 'chunk',
      render: (kb: KnowledgeBase) => (
        <Text type="secondary">{kb.chunkSize} / {kb.chunkOverlap}</Text>
      ),
    },
    {
      title: '状态',
      key: 'status',
      render: (kb: KnowledgeBase) => (
        <Tag color={kb.status === 'ACTIVE' ? 'green' : 'default'}>{kb.status}</Tag>
      ),
    },
    {
      title: '创建时间',
      key: 'createdAt',
      render: (kb: KnowledgeBase) => (
        <Text type="secondary">{new Date(kb.createdAt).toLocaleDateString('zh-CN')}</Text>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (kb: KnowledgeBase) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<FolderOpenOutlined />}
            onClick={() => navigate(`/knowledge-bases/${kb.id}/documents`)}
          >
            文档
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => { setEditing(kb); setModalOpen(true) }}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除？"
            description="将级联删除所有文档和 Chunks，不可恢复。"
            onConfirm={() => deleteMutation.mutate(kb.id)}
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>知识库管理</Title>
          <Text type="secondary" style={{ fontSize: 12 }}>管理所有知识库，配置摄入参数</Text>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => { setEditing(null); setModalOpen(true) }}
        >
          新建知识库
        </Button>
      </div>

      {/* Stats */}
      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="知识库总数" value={data?.totalElements ?? 0} />
          </Card>
        </Col>
      </Row>

      {/* Table */}
      <Card
        title="知识库列表"
        extra={
          <Input.Search
            placeholder="搜索名称"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ width: 200 }}
            allowClear
          />
        }
      >
        <Table
          rowKey="id"
          dataSource={filtered}
          columns={columns}
          loading={isLoading}
          pagination={{ pageSize: 10, showTotal: total => `共 ${total} 条` }}
        />
      </Card>

      <KbFormModal
        open={modalOpen}
        editing={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  )
}
```

- [ ] **Step 4: 更新 App.tsx 使用真实组件**

```tsx
// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import KbListPage from './pages/knowledge-base/KbListPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<KbListPage />} />
        <Route path="knowledge-bases/:kbId/documents" element={<div>文档管理（占位）</div>} />
        <Route path="chat" element={<div>Chat 测试台（占位）</div>} />
      </Route>
    </Routes>
  )
}
```

- [ ] **Step 5: 手动验证（需后端运行）**

启动后端（`./mvnw spring-boot:run`），然后：
```bash
cd admin && npm run dev
```
验证：
- `/knowledge-bases` 页面正常加载，显示 Table 和统计卡片
- "新建知识库"按钮 → Modal 弹出，填写表单 → 提交 → 列表刷新
- 编辑、删除操作正常

- [ ] **Step 6: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/src/hooks/useKbList.ts admin/src/pages/knowledge-base/ admin/src/App.tsx
git commit -m "feat(admin): add knowledge base list page with CRUD modal"
```

---

## Task 6: 文档管理页

**Files:**
- Create: `admin/src/hooks/useDocList.ts`
- Create: `admin/src/pages/document/ChunkDrawer.tsx`
- Create: `admin/src/pages/document/ErrorModal.tsx`
- Create: `admin/src/pages/document/DocListPage.tsx`
- Modify: `admin/src/App.tsx`

- [ ] **Step 1: 创建 useDocList hook（含自动轮询）**

```typescript
// admin/src/hooks/useDocList.ts
import { useQuery } from '@tanstack/react-query'
import { listDocuments } from '../api/document'
import type { DocStatus } from '../types/api'

const ACTIVE_STATUSES: DocStatus[] = ['PENDING', 'PROCESSING']

export const docListKey = (kbId: string) => ['docs', kbId] as const

export function useDocList(kbId: string) {
  return useQuery({
    queryKey: docListKey(kbId),
    queryFn: () => listDocuments(kbId),
    enabled: !!kbId,
    refetchInterval: query => {
      const docs = query.state.data?.content ?? []
      const hasActive = docs.some(d => ACTIVE_STATUSES.includes(d.status))
      return hasActive ? 3000 : false
    },
  })
}
```

- [ ] **Step 2: 创建 ChunkDrawer**

```tsx
// admin/src/pages/document/ChunkDrawer.tsx
import { Drawer, List, Typography, Spin, Empty } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { listChunks } from '../../api/document'

const { Text, Paragraph } = Typography

interface Props {
  open: boolean
  kbId: string
  docId: string | null
  docName: string
  onClose: () => void
}

export default function ChunkDrawer({ open, kbId, docId, docName, onClose }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ['chunks', kbId, docId],
    queryFn: () => listChunks(kbId, docId!),
    enabled: open && !!docId,
  })

  return (
    <Drawer
      title={`Chunks — ${docName}`}
      open={open}
      onClose={onClose}
      width={600}
    >
      {isLoading ? (
        <div style={{ textAlign: 'center', paddingTop: 40 }}><Spin /></div>
      ) : !data?.length ? (
        <Empty description="暂无 Chunk" />
      ) : (
        <List
          dataSource={data}
          renderItem={chunk => (
            <List.Item key={chunk.id} style={{ alignItems: 'flex-start' }}>
              <div style={{ width: '100%' }}>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  #{chunk.chunkIndex}
                </Text>
                <Paragraph
                  style={{
                    background: '#f5f5f5',
                    padding: '8px 12px',
                    borderRadius: 4,
                    marginTop: 4,
                    marginBottom: 0,
                    fontSize: 13,
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {chunk.content}
                </Paragraph>
              </div>
            </List.Item>
          )}
        />
      )}
    </Drawer>
  )
}
```

- [ ] **Step 3: 创建 ErrorModal**

```tsx
// admin/src/pages/document/ErrorModal.tsx
import { Modal, Typography, Alert } from 'antd'

const { Text } = Typography

interface Props {
  open: boolean
  docName: string
  errorMessage: string | null
  onClose: () => void
}

export default function ErrorModal({ open, docName, errorMessage, onClose }: Props) {
  return (
    <Modal
      title={`摄入错误 — ${docName}`}
      open={open}
      onCancel={onClose}
      footer={null}
    >
      <Alert
        type="error"
        message="摄入失败"
        description={
          <Text code style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
            {errorMessage ?? '未知错误'}
          </Text>
        }
        showIcon
      />
    </Modal>
  )
}
```

- [ ] **Step 4: 创建 DocListPage**

```tsx
// admin/src/pages/document/DocListPage.tsx
import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Upload, Table, Tag, Button, Space, Card, Typography,
  Spin, message, Tooltip,
} from 'antd'
import {
  UploadOutlined, ArrowLeftOutlined,
  FileTextOutlined, EyeOutlined, DeleteOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { uploadDocument, deleteDocument } from '../../api/document'
import { useDocList, docListKey } from '../../hooks/useDocList'
import ChunkDrawer from './ChunkDrawer'
import ErrorModal from './ErrorModal'
import type { KbDocument, DocStatus } from '../../types/api'

const { Title, Text } = Typography
const { Dragger } = Upload

const STATUS_COLOR: Record<DocStatus, string> = {
  PENDING: 'default',
  PROCESSING: 'orange',
  DONE: 'green',
  FAILED: 'red',
}

export default function DocListPage() {
  const { kbId = '' } = useParams<{ kbId: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data, isLoading } = useDocList(kbId)

  const [chunkDrawer, setChunkDrawer] = useState<{ docId: string; docName: string } | null>(null)
  const [errorModal, setErrorModal] = useState<{ docName: string; msg: string } | null>(null)

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadDocument(kbId, file),
    onSuccess: () => {
      message.success('上传成功，正在摄入...')
      qc.invalidateQueries({ queryKey: docListKey(kbId) })
    },
    onError: (e: Error) => message.error(e.message),
  })

  const deleteMutation = useMutation({
    mutationFn: (docId: string) => deleteDocument(kbId, docId),
    onSuccess: () => {
      message.success('删除成功')
      qc.invalidateQueries({ queryKey: docListKey(kbId) })
    },
    onError: (e: Error) => message.error(e.message),
  })

  const docs = data?.content ?? []
  const hasActive = docs.some(d => d.status === 'PENDING' || d.status === 'PROCESSING')

  const columns = [
    {
      title: '文件名',
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Text strong>{name}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'fileType',
      key: 'fileType',
      render: (t: string) => <Tag>{t}</Tag>,
    },
    {
      title: '状态',
      key: 'status',
      render: (doc: KbDocument) => (
        <Tag color={STATUS_COLOR[doc.status]}>
          {doc.status === 'PROCESSING' && '⟳ '}{doc.status}
        </Tag>
      ),
    },
    {
      title: 'Chunk 数',
      dataIndex: 'chunkCount',
      key: 'chunkCount',
      render: (n: number, doc: KbDocument) =>
        doc.status === 'DONE' ? n : <Text type="secondary">—</Text>,
    },
    {
      title: '上传时间',
      key: 'createdAt',
      render: (doc: KbDocument) =>
        new Date(doc.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }),
    },
    {
      title: '操作',
      key: 'actions',
      render: (doc: KbDocument) => (
        <Space>
          {doc.status === 'DONE' && (
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => setChunkDrawer({ docId: doc.id, docName: doc.name })}
            >
              查看 Chunks
            </Button>
          )}
          {doc.status === 'FAILED' && (
            <Button
              type="link"
              size="small"
              danger
              icon={<WarningOutlined />}
              onClick={() => setErrorModal({ docName: doc.name, msg: '摄入失败（errorMessage 字段）' })}
            >
              查看错误
            </Button>
          )}
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            disabled={doc.status === 'PROCESSING'}
            onClick={() => deleteMutation.mutate(doc.id)}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge-bases')}>
          返回
        </Button>
        <div>
          <Title level={4} style={{ margin: 0 }}>文档管理</Title>
          <Text type="secondary" style={{ fontSize: 12 }}>知识库 ID: {kbId}</Text>
        </div>
      </div>

      {/* Upload */}
      <Card style={{ marginBottom: 16 }}>
        <Dragger
          multiple={false}
          accept=".pdf,.docx,.doc,.md,.txt"
          showUploadList={false}
          customRequest={({ file, onSuccess, onError }) => {
            uploadMutation.mutate(file as File, {
              onSuccess: () => onSuccess?.('ok'),
              onError: e => onError?.(e as Error),
            })
          }}
        >
          <p style={{ fontSize: 20 }}><UploadOutlined /></p>
          <p style={{ fontWeight: 500 }}>点击或拖拽文件到此区域上传</p>
          <p style={{ color: '#8c8c8c', fontSize: 12 }}>支持 PDF、DOCX、TXT、MD，异步摄入处理</p>
        </Dragger>
      </Card>

      {/* Doc list */}
      <Card
        title="文档列表"
        extra={hasActive && (
          <Tag color="orange">⟳ 处理中，自动刷新</Tag>
        )}
      >
        <Table
          rowKey="id"
          dataSource={docs}
          columns={columns}
          loading={isLoading}
          rowClassName={doc =>
            doc.status === 'PROCESSING' ? 'processing-row' : ''
          }
          pagination={{ pageSize: 10 }}
        />
      </Card>

      <ChunkDrawer
        open={!!chunkDrawer}
        kbId={kbId}
        docId={chunkDrawer?.docId ?? null}
        docName={chunkDrawer?.docName ?? ''}
        onClose={() => setChunkDrawer(null)}
      />
      <ErrorModal
        open={!!errorModal}
        docName={errorModal?.docName ?? ''}
        errorMessage={errorModal?.msg ?? null}
        onClose={() => setErrorModal(null)}
      />
    </div>
  )
}
```

> **注**：后端 `DocumentListItem` 不含 `errorMessage` 字段，FAILED 行的错误信息目前只能显示占位文字。如需显示真实错误，需后端在文档列表接口中增加该字段。

- [ ] **Step 5: 更新 App.tsx**

```tsx
// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import KbListPage from './pages/knowledge-base/KbListPage'
import DocListPage from './pages/document/DocListPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<KbListPage />} />
        <Route path="knowledge-bases/:kbId/documents" element={<DocListPage />} />
        <Route path="chat" element={<div>Chat 测试台（占位）</div>} />
      </Route>
    </Routes>
  )
}
```

- [ ] **Step 6: 手动验证**

启动后端，访问 `/knowledge-bases`：
- 点击某行"文档"跳转到 `/knowledge-bases/:kbId/documents`
- 拖拽文件上传 → 列表出现 PENDING 状态条目，3 秒后自动刷新状态
- DONE 状态行可点击"查看 Chunks" → Drawer 展示内容
- "返回"按钮跳回知识库列表

- [ ] **Step 7: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/src/hooks/useDocList.ts admin/src/pages/document/ admin/src/App.tsx
git commit -m "feat(admin): add document management page with upload and auto-polling"
```

---

## Task 7: Chat 测试台

**Files:**
- Create: `admin/src/hooks/useStreamingChat.ts`
- Create: `admin/src/pages/chat/ChunkContextDrawer.tsx`
- Create: `admin/src/pages/chat/ChatPage.tsx`
- Modify: `admin/src/App.tsx`

- [ ] **Step 1: 创建 useStreamingChat hook**

```typescript
// admin/src/hooks/useStreamingChat.ts
import { useState, useCallback } from 'react'
import {
  streamSingleChat,
  streamConversation,
  createConversation,
} from '../api/chat'
import type { ChatMessage, SearchResultItem } from '../types/api'

type ChatMode = 'multi' | 'single'

interface SendOptions {
  question: string
  topK: number
  mode: ChatMode
  knowledgeBaseId: string
  conversationId: string | null
  onConversationCreated: (id: string) => void
}

export function useStreamingChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [streaming, setStreaming] = useState(false)

  const sendMessage = useCallback(async (opts: SendOptions) => {
    const { question, topK, mode, knowledgeBaseId, conversationId, onConversationCreated } = opts

    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: question,
    }
    const aiMsgId = crypto.randomUUID()
    const aiMsg: ChatMessage = {
      id: aiMsgId,
      role: 'assistant',
      content: '',
      streaming: true,
    }
    setMessages(prev => [...prev, userMsg, aiMsg])
    setStreaming(true)

    try {
      let convId = conversationId

      if (mode === 'multi' && !convId) {
        const conv = await createConversation(knowledgeBaseId)
        convId = conv.id
        onConversationCreated(convId)
      }

      const stream =
        mode === 'multi' && convId
          ? await streamConversation(convId, question, topK)
          : await streamSingleChat(knowledgeBaseId, question, topK)

      const reader = stream.getReader()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        setMessages(prev =>
          prev.map(m =>
            m.id === aiMsgId ? { ...m, content: m.content + value } : m,
          ),
        )
      }
      setMessages(prev =>
        prev.map(m => (m.id === aiMsgId ? { ...m, streaming: false } : m)),
      )
    } catch {
      setMessages(prev =>
        prev.map(m =>
          m.id === aiMsgId
            ? { ...m, content: '连接中断，请重试', streaming: false }
            : m,
        ),
      )
    } finally {
      setStreaming(false)
    }
  }, [])

  const attachChunks = useCallback((aiMsgId: string, chunks: SearchResultItem[]) => {
    setMessages(prev =>
      prev.map(m => (m.id === aiMsgId ? { ...m, chunks } : m)),
    )
  }, [])

  const clearMessages = useCallback(() => setMessages([]), [])

  return { messages, streaming, sendMessage, attachChunks, clearMessages }
}
```

- [ ] **Step 2: 创建 ChunkContextDrawer**

```tsx
// admin/src/pages/chat/ChunkContextDrawer.tsx
import { Drawer, List, Typography, Empty } from 'antd'
import type { SearchResultItem } from '../../types/api'

const { Text, Paragraph } = Typography

interface Props {
  open: boolean
  chunks: SearchResultItem[]
  onClose: () => void
}

export default function ChunkContextDrawer({ open, chunks, onClose }: Props) {
  return (
    <Drawer title="召回 Chunks" open={open} onClose={onClose} width={560}>
      {chunks.length === 0 ? (
        <Empty description="暂无召回内容" />
      ) : (
        <List
          dataSource={chunks}
          renderItem={(item, idx) => (
            <List.Item key={idx} style={{ alignItems: 'flex-start' }}>
              <div style={{ width: '100%' }}>
                <Text type="secondary" style={{ fontSize: 11 }}>#{idx + 1}</Text>
                <Paragraph
                  style={{
                    background: '#f5f5f5',
                    padding: '8px 12px',
                    borderRadius: 4,
                    marginTop: 4,
                    marginBottom: 0,
                    fontSize: 13,
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {item.content}
                </Paragraph>
              </div>
            </List.Item>
          )}
        />
      )}
    </Drawer>
  )
}
```

- [ ] **Step 3: 创建 ChatPage**

```tsx
// admin/src/pages/chat/ChatPage.tsx
import { useState, useRef, useEffect, KeyboardEvent } from 'react'
import {
  Select, Button, Typography, Input, Segmented,
  Avatar, Spin, Space, Card, Tag, message,
} from 'antd'
import {
  SendOutlined, ClearOutlined, PaperClipOutlined, RobotOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation } from '@tanstack/react-query'
import { listKbs } from '../../api/knowledge-base'
import { searchKb } from '../../api/document'
import { useStreamingChat } from '../../hooks/useStreamingChat'
import ChunkContextDrawer from './ChunkContextDrawer'
import type { ChatMessage, SearchResultItem } from '../../types/api'

const { Text } = Typography
const { TextArea } = Input

export default function ChatPage() {
  const { data: kbData } = useQuery({
    queryKey: ['kb-list'],
    queryFn: () => listKbs(0, 50),
  })
  const kbs = kbData?.content ?? []

  const [selectedKbId, setSelectedKbId] = useState<string>('')
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [mode, setMode] = useState<'multi' | 'single'>('multi')
  const [topK, setTopK] = useState(5)
  const [input, setInput] = useState('')
  const [chunkDrawer, setChunkDrawer] = useState<{ msgId: string; chunks: SearchResultItem[] } | null>(null)

  const { messages, streaming, sendMessage, attachChunks, clearMessages } = useStreamingChat()
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleKbChange = (kbId: string) => {
    setSelectedKbId(kbId)
    setConversationId(null)
    clearMessages()
  }

  const handleSend = async () => {
    const q = input.trim()
    if (!q || !selectedKbId || streaming) return
    setInput('')
    await sendMessage({
      question: q,
      topK,
      mode,
      knowledgeBaseId: selectedKbId,
      conversationId,
      onConversationCreated: id => setConversationId(id),
    })
    // After streaming completes, fetch context chunks
    try {
      const chunks = await searchKb(selectedKbId, q, topK)
      // find last assistant message
      const lastAiMsg = [...messages].reverse().find(m => m.role === 'assistant')
      if (lastAiMsg) attachChunks(lastAiMsg.id, chunks)
    } catch {
      // best-effort, ignore errors
    }
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div style={{ height: 'calc(100vh - 88px)', display: 'flex', flexDirection: 'column' }}>
      {/* Top bar */}
      <Card
        size="small"
        style={{ marginBottom: 12, flexShrink: 0 }}
        bodyStyle={{ display: 'flex', alignItems: 'center', gap: 12 }}
      >
        <Text style={{ fontSize: 13, whiteSpace: 'nowrap' }}>知识库：</Text>
        <Select
          placeholder="选择知识库"
          style={{ width: 220 }}
          value={selectedKbId || undefined}
          onChange={handleKbChange}
          options={kbs.map(kb => ({ value: kb.id, label: kb.name }))}
        />
        <Button
          icon={<ClearOutlined />}
          onClick={() => { clearMessages(); setConversationId(null) }}
          disabled={messages.length === 0}
        >
          清空对话
        </Button>
      </Card>

      {/* Messages */}
      <Card
        style={{ flex: 1, overflow: 'hidden', marginBottom: 12 }}
        bodyStyle={{ height: '100%', overflow: 'auto', padding: '16px 20px' }}
      >
        {messages.length === 0 && (
          <div style={{ textAlign: 'center', paddingTop: 60, color: '#8c8c8c' }}>
            {selectedKbId ? '输入问题开始对话' : '请先选择知识库'}
          </div>
        )}
        {messages.map(msg => (
          <MessageBubble
            key={msg.id}
            msg={msg}
            onViewChunks={chunks => setChunkDrawer({ msgId: msg.id, chunks })}
          />
        ))}
        <div ref={bottomRef} />
      </Card>

      {/* Input area */}
      <Card size="small" style={{ flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>召回数量 topK：</Text>
          <Button size="small" onClick={() => setTopK(v => Math.max(1, v - 1))}>−</Button>
          <Tag color="blue" style={{ margin: 0, minWidth: 28, textAlign: 'center' }}>{topK}</Tag>
          <Button size="small" onClick={() => setTopK(v => Math.min(20, v + 1))}>＋</Button>
          <div style={{ marginLeft: 16 }}>
            <Segmented
              size="small"
              value={mode}
              onChange={v => { setMode(v as 'multi' | 'single'); setConversationId(null); clearMessages() }}
              options={[
                { label: '多轮对话', value: 'multi' },
                { label: '单次问答', value: 'single' },
              ]}
            />
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <TextArea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="输入问题，Enter 发送，Shift+Enter 换行..."
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={streaming || !selectedKbId}
            style={{ flex: 1 }}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={handleSend}
            disabled={!input.trim() || !selectedKbId || streaming}
            style={{ height: 'auto', alignSelf: 'stretch' }}
          >
            发送
          </Button>
        </div>
      </Card>

      <ChunkContextDrawer
        open={!!chunkDrawer}
        chunks={chunkDrawer?.chunks ?? []}
        onClose={() => setChunkDrawer(null)}
      />
    </div>
  )
}

function MessageBubble({
  msg,
  onViewChunks,
}: {
  msg: ChatMessage
  onViewChunks: (chunks: SearchResultItem[]) => void
}) {
  const isUser = msg.role === 'user'
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        marginBottom: 16,
        gap: 10,
      }}
    >
      {!isUser && (
        <Avatar icon={<RobotOutlined />} style={{ background: '#f0f0f0', color: '#8c8c8c', flexShrink: 0 }} />
      )}
      <div style={{ maxWidth: '70%' }}>
        <div
          style={{
            background: isUser ? '#1677ff' : '#fff',
            color: isUser ? '#fff' : '#000000d9',
            padding: '10px 14px',
            borderRadius: isUser ? '12px 12px 2px 12px' : '2px 12px 12px 12px',
            border: isUser ? 'none' : '1px solid #f0f0f0',
            fontSize: 13,
            lineHeight: 1.7,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {msg.content}
          {msg.streaming && (
            <span style={{ borderRight: '2px solid', paddingRight: 1, marginLeft: 2, animation: 'blink 1s step-end infinite' }} />
          )}
        </div>
        {!isUser && !msg.streaming && (
          <div style={{ marginTop: 4 }}>
            <Button
              type="text"
              size="small"
              icon={<PaperClipOutlined />}
              style={{ fontSize: 11, color: '#8c8c8c', padding: '0 4px' }}
              onClick={() => onViewChunks(msg.chunks ?? [])}
            >
              查看召回 Chunks {msg.chunks ? `(${msg.chunks.length})` : ''}
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
```

- [ ] **Step 4: 更新 App.tsx（最终版）**

```tsx
// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import KbListPage from './pages/knowledge-base/KbListPage'
import DocListPage from './pages/document/DocListPage'
import ChatPage from './pages/chat/ChatPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<KbListPage />} />
        <Route path="knowledge-bases/:kbId/documents" element={<DocListPage />} />
        <Route path="chat" element={<ChatPage />} />
      </Route>
    </Routes>
  )
}
```

- [ ] **Step 5: 添加光标闪烁 CSS**

在 `admin/src/main.tsx` 顶部 import 之后添加全局样式：

```tsx
// admin/src/main.tsx — 在 import 'antd/dist/reset.css' 后追加：
import './index.css'
```

创建 `admin/src/index.css`：

```css
/* admin/src/index.css */
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.processing-row {
  background-color: #fffbe6 !important;
}
```

- [ ] **Step 6: TypeScript 类型检查**

```bash
cd admin && npx tsc --noEmit
```

Expected: 无错误。

- [ ] **Step 7: 手动验证 Chat 测试台**

启动后端，访问 `http://localhost:5173/chat`：
- 选择知识库 → 输入问题 → 点击发送
- AI 回复气泡出现并逐字流式输出，末尾光标闪烁
- 输出完成后"查看召回 Chunks"按钮出现，点击展开 Drawer
- 切换"单次问答"模式，验证无会话 ID 的调用方式
- 切换知识库，对话历史清空

- [ ] **Step 8: 运行所有测试**

```bash
cd admin && npm run test -- --run
```

Expected: `Test Files 1 passed`（api.client.test.ts）

- [ ] **Step 9: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add admin/src/hooks/useStreamingChat.ts admin/src/pages/chat/ admin/src/App.tsx admin/src/index.css admin/src/main.tsx
git commit -m "feat(admin): add Chat test console with SSE streaming and chunk context"
```

---

## Task 8: 收尾与 .gitignore

**Files:**
- Modify: `.gitignore`（项目根）

- [ ] **Step 1: 确认 admin/node_modules 在 gitignore 中**

```bash
grep -n "node_modules" /Users/majunmin/workspace/learn/rag0429/.gitignore || echo "missing"
```

若输出 `missing`，在 `.gitignore` 末尾追加：

```
# admin frontend
admin/node_modules/
admin/dist/
```

- [ ] **Step 2: 确认 .superpowers 在 gitignore 中**

```bash
grep -n "superpowers" /Users/majunmin/workspace/learn/rag0429/.gitignore || echo "missing"
```

若输出 `missing`，追加：

```
.superpowers/
```

- [ ] **Step 3: 最终全量测试**

```bash
# 在项目根目录
./mvnw test -q                     # 后端单元测试
cd admin && npm run test -- --run  # 前端单元测试
```

Expected: 后端和前端测试均通过。

- [ ] **Step 4: Commit**

```bash
cd /Users/majunmin/workspace/learn/rag0429
git add .gitignore
git commit -m "chore: add admin/node_modules and .superpowers to gitignore"
```

---

## 自审结果

| 规格要求 | 对应 Task |
|---|---|
| 固定侧边栏布局 | Task 4 AppLayout |
| React Router v6 路由 | Task 2 + App.tsx 各 Task |
| TanStack Query 服务端状态 | Task 3 hooks，Task 5/6/7 |
| 知识库 CRUD（列表/新建/编辑/删除） | Task 5 |
| 文档上传 + 状态 Table | Task 6 |
| PENDING/PROCESSING 自动轮询（3s） | Task 6 useDocList |
| Chunk 内容 Drawer | Task 6 ChunkDrawer |
| FAILED 错误 Modal | Task 6 ErrorModal |
| SSE 流式 Chat | Task 7 useStreamingChat |
| 多轮 / 单次模式切换 | Task 7 ChatPage |
| topK 调节 | Task 7 ChatPage |
| 召回 Chunks 查看（补调 /search） | Task 7 ChatPage + ChunkContextDrawer |
| Vite proxy 开发代理 | Task 2 vite.config.ts |
| 后端 CORS 配置 | Task 1 |
| API client 单元测试（Vitest） | Task 3 |
