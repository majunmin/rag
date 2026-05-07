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
