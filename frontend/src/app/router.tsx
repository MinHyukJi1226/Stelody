import { createBrowserRouter } from 'react-router'
import { HomePage } from '@/pages/home/home-page'
import { NotFoundPage } from '@/pages/not-found/not-found-page'
import { ComingSoonPage } from '@/pages/coming-soon/coming-soon-page'
import { SiteLayout } from './layout/site-layout'

export const router = createBrowserRouter([
  {
    element: <SiteLayout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/search', element: <ComingSoonPage title="검색" /> },
      { path: '/members', element: <ComingSoonPage title="멤버" /> },
      { path: '/library', element: <ComingSoonPage title="내 보관함" /> },
      { path: '/login', element: <ComingSoonPage title="로그인" /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
