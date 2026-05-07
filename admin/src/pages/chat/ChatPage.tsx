import { useState, useRef, useEffect, KeyboardEvent } from 'react'
import {
  Select, Button, Typography, Input, Segmented,
  Avatar, Card, Tag,
} from 'antd'
import {
  SendOutlined, ClearOutlined, PaperClipOutlined, RobotOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
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
    const aiMsgId = await sendMessage({
      question: q,
      topK,
      mode,
      knowledgeBaseId: selectedKbId,
      conversationId,
      onConversationCreated: id => setConversationId(id),
    })
    try {
      const chunks = await searchKb(selectedKbId, q, topK)
      attachChunks(aiMsgId, chunks)
    } catch {
      // best-effort
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
      <Card
        size="small"
        style={{ marginBottom: 12, flexShrink: 0 }}
        styles={{ body: { display: 'flex', alignItems: 'center', gap: 12 } }}
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

      <Card
        style={{ flex: 1, overflow: 'hidden', marginBottom: 12 }}
        styles={{ body: { height: '100%', overflow: 'auto', padding: '16px 20px' } }}
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
