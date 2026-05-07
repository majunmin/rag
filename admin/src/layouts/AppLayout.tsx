// admin/src/layouts/AppLayout.tsx
import { useState, useEffect } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Breadcrumb, Badge } from 'antd'
import {
  DatabaseOutlined,
  FileTextOutlined,
  MessageOutlined,
} from '@ant-design/icons'

const { Sider, Header, Content } = Layout

const NAV_ITEMS = [
  { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库管理' },
  { key: '/knowledge-bases', icon: <FileTextOutlined />, label: '文档管理' },
  { key: '/chat', icon: <MessageOutlined />, label: 'Chat 测试台' },
]

function useBreadcrumb(pathname: string): string[] {
  if (pathname.startsWith('/knowledge-bases') && pathname.includes('/documents'))
    return ['知识库管理', '文档管理']
  if (pathname.startsWith('/knowledge-bases')) return ['知识库管理']
  if (pathname.startsWith('/chat')) return ['Chat 测试台']
  return []
}

function activeKey(pathname: string): string {
  if (pathname.startsWith('/chat')) return '/chat'
  return '/knowledge-bases'
}

export default function AppLayout() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [healthy, setHealthy] = useState<boolean | null>(null)
  const breadcrumbs = useBreadcrumb(pathname)

  useEffect(() => {
    const check = () =>
      fetch('/api/actuator/health')
        .then(r => setHealthy(r.ok))
        .catch(() => setHealthy(false))
    check()
    const timer = setInterval(check, 30_000)
    return () => clearInterval(timer)
  }, [])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark" style={{ position: 'fixed', height: '100vh', left: 0, top: 0 }}>
        <div
          style={{
            padding: '16px 20px',
            borderBottom: '1px solid rgba(255,255,255,0.08)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <div
            style={{
              width: 28, height: 28,
              background: '#1677ff',
              borderRadius: 6,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontWeight: 700, fontSize: 14, color: '#fff',
            }}
          >
            R
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 600, color: '#fff' }}>RAG Admin</div>
            <div style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)' }}>Knowledge Platform</div>
          </div>
        </div>

        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[activeKey(pathname)]}
          style={{ borderRight: 0, marginTop: 8 }}
          items={NAV_ITEMS.map(item => ({
            key: item.key,
            icon: item.icon,
            label: item.label,
          }))}
          onClick={({ key }) => navigate(key)}
        />

        <div
          style={{
            position: 'absolute', bottom: 0, left: 0, right: 0,
            padding: '12px 20px',
            borderTop: '1px solid rgba(255,255,255,0.08)',
            color: 'rgba(255,255,255,0.3)',
            fontSize: 11,
          }}
        >
          API: localhost:8080
        </div>
      </Sider>

      <Layout style={{ marginLeft: 220 }}>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            height: 48,
            lineHeight: '48px',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky', top: 0, zIndex: 10,
          }}
        >
          <Breadcrumb
            items={[{ title: '首页' }, ...breadcrumbs.map(b => ({ title: b }))]}
            style={{ fontSize: 13 }}
          />
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            {healthy === null ? null : healthy ? (
              <Badge color="green" text={<span style={{ color: '#52c41a' }}>后端在线</span>} />
            ) : (
              <Badge color="red" text={<span style={{ color: '#ff4d4f' }}>后端离线</span>} />
            )}
          </div>
        </Header>

        <Content style={{ background: '#f5f5f5', minHeight: 'calc(100vh - 48px)', padding: 20 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
