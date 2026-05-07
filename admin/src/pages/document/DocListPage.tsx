// admin/src/pages/document/DocListPage.tsx
import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Upload, Table, Tag, Button, Space, Card, Typography,
  message,
} from 'antd'
import {
  UploadOutlined, ArrowLeftOutlined,
  EyeOutlined, DeleteOutlined,
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
