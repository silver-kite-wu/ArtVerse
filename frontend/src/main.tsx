/* eslint-disable react-refresh/only-export-components -- 入口文件按路由条件选择根组件，无需 Fast Refresh 导出 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './studio.css'
import './profile.css'
import './components/ai-composer.css'
import App from './App.tsx'
import ErrorBoundary from './ErrorBoundary.tsx'
import GuardDashboardPage from './components/GuardDashboardPage.tsx'
import ToastProvider from './ui/toast/ToastProvider.tsx'
import ConfirmProvider from './ui/confirm/ConfirmProvider.tsx'

const Root = window.location.pathname === '/internal/guard'
  ? GuardDashboardPage
  : App

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <ToastProvider>
        <ConfirmProvider>
          <Root />
        </ConfirmProvider>
      </ToastProvider>
    </ErrorBoundary>
  </StrictMode>,
)
