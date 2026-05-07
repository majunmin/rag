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
