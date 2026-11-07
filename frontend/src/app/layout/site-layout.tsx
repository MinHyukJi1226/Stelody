import { PageActionProvider } from '@/shared/ui/page-action-provider'
import { Outlet, ScrollRestoration, useLocation } from 'react-router'
import { HomeHeader } from '@/pages/home/components/home-header'
import { SiteHeader } from './site-header'
import { useTheme } from '../use-theme'
import { FavoriteResume } from '@/features/favorites/favorite-resume'

export function SiteLayout() {
  const { theme, toggleTheme } = useTheme()
  const home = useLocation().pathname === '/'
  return (
    <PageActionProvider>
      <div
        className={
          home
            ? `flex min-h-dvh flex-col ${theme === 'dark' ? 'bg-bg' : 'bg-surface'}`
            : 'flex min-h-dvh flex-col'
        }
      >
        <a
          href="#main-content"
          className="fixed left-6 top-4 z-50 -translate-y-24 rounded-xl bg-brand-fill p-4 text-on-accent focus:translate-y-0"
        >
          본문으로 건너뛰기
        </a>
        {home ? (
          <HomeHeader theme={theme} toggleTheme={toggleTheme} />
        ) : (
          <SiteHeader theme={theme} toggleTheme={toggleTheme} />
        )}
        <main
          id="main-content"
          tabIndex={-1}
          className={home ? 'w-full flex-1 px-5 pt-3 md:px-10' : 'page-content'}
        >
          <Outlet />
        </main>
        <ScrollRestoration />
        <FavoriteResume />
      </div>
    </PageActionProvider>
  )
}
