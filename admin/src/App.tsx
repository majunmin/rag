// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<div>知识库管理（占位）</div>} />
        <Route path="knowledge-bases/:kbId/documents" element={<div>文档管理（占位）</div>} />
        <Route path="chat" element={<div>Chat 测试台（占位）</div>} />
      </Route>
    </Routes>
  )
}
