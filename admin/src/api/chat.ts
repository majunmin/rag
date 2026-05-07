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
