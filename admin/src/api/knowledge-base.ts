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
