import { createBrowserRouter } from 'react-router'
import { HomePage } from '@/pages/home/home-page'
import { NotFoundPage } from '@/pages/not-found/not-found-page'
import { ComingSoonPage } from '@/pages/coming-soon/coming-soon-page'
import { SongCatalogPage } from '@/pages/song-catalog/song-catalog-page'
import { StatePanel } from '@/shared/ui/state-panel'
import { SiteLayout } from './layout/site-layout'

export const router = createBrowserRouter([
  {
    element: <SiteLayout />,
    HydrateFallback: () => <StatePanel title="화면 불러오는 중…" />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/songs', element: <SongCatalogPage /> },
      { path: '/songs/:songId', element: <ComingSoonPage title="노래 상세" /> },
      {
        path: '/members/:memberId',
        element: <ComingSoonPage title="멤버 상세" />,
      },
      {
        path: '/search',
        lazy: async () => {
          const { SearchPage } = await import('@/pages/search/search-page')
          return { Component: SearchPage }
        },
      },
      { path: '/members', element: <ComingSoonPage title="멤버" /> },
      { path: '/library', element: <ComingSoonPage title="내 보관함" /> },
      { path: '/login', element: <ComingSoonPage title="로그인" /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
