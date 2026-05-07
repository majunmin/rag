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
