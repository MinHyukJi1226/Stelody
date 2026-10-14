import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/app'
import './app/styles.css'
import { initializeTheme } from './shared/lib/theme'

const root = document.getElementById('root')
if (!root) throw new Error('앱을 표시할 영역이 없습니다.')
initializeTheme()
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
