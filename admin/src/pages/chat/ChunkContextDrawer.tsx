// admin/src/pages/chat/ChunkContextDrawer.tsx
import { Drawer, List, Typography, Empty } from 'antd'
import type { SearchResultItem } from '../../types/api'

const { Text, Paragraph } = Typography

interface Props {
  open: boolean
  chunks: SearchResultItem[]
  onClose: () => void
}

export default function ChunkContextDrawer({ open, chunks, onClose }: Props) {
  return (
    <Drawer title="召回 Chunks" open={open} onClose={onClose} width={560}>
      {chunks.length === 0 ? (
        <Empty description="暂无召回内容" />
      ) : (
        <List
          dataSource={chunks}
          renderItem={(item, idx) => (
            <List.Item key={idx} style={{ alignItems: 'flex-start' }}>
              <div style={{ width: '100%' }}>
                <Text type="secondary" style={{ fontSize: 11 }}>#{idx + 1}</Text>
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
                  {item.content}
                </Paragraph>
              </div>
            </List.Item>
          )}
        />
      )}
    </Drawer>
  )
}
