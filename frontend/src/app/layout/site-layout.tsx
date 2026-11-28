import { useLayoutEffect } from 'react'
import { pageTitle } from '../page-title'
import { PageActionProvider } from '@/shared/ui/page-action-provider'
import {
  matchPath,
  Outlet,
  ScrollRestoration,
  useLocation,
  useMatches,
} from 'react-router'
import { SiteHeader } from './site-header'
import { SiteFooter } from '@/shared/ui/site-footer'
import { useTheme } from '../use-theme'
import { FavoriteResume } from '@/features/favorites/favorite-resume'

export function SiteLayout() {
  const { theme, toggleTheme } = useTheme()
  const pathname = useLocation().pathname
  const notFound = useMatches().some((match) => match.id === 'not-found')
  useLayoutEffect(() => {
    const title = pageTitle(pathname, notFound)
    if (title) document.title = title
  }, [pathname, notFound])
  const home = pathname === '/'
  const search = pathname === '/search'
  const login = pathname === '/login'
  const legal = pathname === '/privacy' || pathname === '/terms'
  const account = pathname === '/account' || pathname === '/account/withdrawal'
  const catalog = pathname === '/songs'
  const songDetail = !!matchPath('/songs/:songId', pathname)
  const members = pathname === '/members' || pathname.startsWith('/members/')
  const library = pathname === '/library' || pathname === '/library/playlists'
  const playlistPage =
    !!matchPath('/library/playlists/:playlistId', pathname) ||
    !!matchPath('/library/playlists/:playlistId/edit', pathname) ||
    !!matchPath('/library/playlists/:playlistId/youtube-export', pathname)
  const compact =
    home ||
    login ||
    legal ||
    account ||
    search ||
    catalog ||
    songDetail ||
    members ||
    library ||
    playlistPage ||
    notFound
  return (
    <PageActionProvider>
      <div
        className={
          compact
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
        <SiteHeader theme={theme} toggleTheme={toggleTheme} />
        <main
          id="main-content"
          tabIndex={-1}
          className={
            home
              ? 'w-full flex-1 px-5 pt-3 md:px-10'
              : login ||
                  legal ||
                  account ||
                  search ||
                  catalog ||
                  songDetail ||
                  members ||
                  library ||
                  playlistPage ||
                  notFound
                ? 'flex w-full flex-1 flex-col'
                : 'page-content'
          }
        >
          <Outlet />
        </main>
        <SiteFooter theme={theme} />
        <ScrollRestoration />
        <FavoriteResume />
      </div>
    </PageActionProvider>
  )
}
