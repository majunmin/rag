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

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="知识库总数" value={data?.totalElements ?? 0} />
          </Card>
        </Col>
      </Row>

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
