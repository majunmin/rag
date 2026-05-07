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
