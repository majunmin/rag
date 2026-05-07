// admin/src/pages/document/ErrorModal.tsx
import { Modal, Typography, Alert } from 'antd'

const { Text } = Typography

interface Props {
  open: boolean
  docName: string
  errorMessage: string | null
  onClose: () => void
}

export default function ErrorModal({ open, docName, errorMessage, onClose }: Props) {
  return (
    <Modal
      title={`摄入错误 — ${docName}`}
      open={open}
      onCancel={onClose}
      footer={null}
    >
      <Alert
        type="error"
        message="摄入失败"
        description={
          <Text code style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
            {errorMessage ?? '未知错误'}
          </Text>
        }
        showIcon
      />
    </Modal>
  )
}
