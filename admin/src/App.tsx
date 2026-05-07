// admin/src/App.tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import KbListPage from './pages/knowledge-base/KbListPage'
import DocListPage from './pages/document/DocListPage'
import ChatPage from './pages/chat/ChatPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/knowledge-bases" replace />} />
        <Route path="knowledge-bases" element={<KbListPage />} />
        <Route path="knowledge-bases/:kbId/documents" element={<DocListPage />} />
        <Route path="chat" element={<ChatPage />} />
      </Route>
    </Routes>
  )
}
