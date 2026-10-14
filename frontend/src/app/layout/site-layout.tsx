import { Outlet } from 'react-router'
import { SiteHeader } from './site-header'
import { useTheme } from '../use-theme'

export function SiteLayout() {
  const { theme, toggleTheme } = useTheme()
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main-content"
        className="fixed left-6 top-4 z-50 -translate-y-24 rounded-xl bg-brand-fill p-4 text-on-accent focus:translate-y-0"
      >
        본문으로 건너뛰기
      </a>
      <SiteHeader theme={theme} toggleTheme={toggleTheme} />
      <main id="main-content" tabIndex={-1} className="page-content">
        <Outlet />
      </main>
    </div>
  )
}
