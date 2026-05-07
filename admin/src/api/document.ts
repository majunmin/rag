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
