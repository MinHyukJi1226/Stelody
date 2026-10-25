import { createBrowserRouter } from 'react-router'
import { HomePage } from '@/pages/home/home-page'
import { NotFoundPage } from '@/pages/not-found/not-found-page'
import { SongCatalogPage } from '@/pages/song-catalog/song-catalog-page'
import { StatePanel } from '@/shared/ui/state-panel'
import { SiteLayout } from './layout/site-layout'
import { AuthGate } from '@/features/auth/auth-gate'

export const router = createBrowserRouter([
  {
    element: <SiteLayout />,
    HydrateFallback: () => <StatePanel title="화면 불러오는 중…" />,
    children: [
      { path: '/', element: <HomePage /> },
      {
        path: '/admin',
        lazy: async () => {
          const { AdminGate } = await import('@/pages/admin/admin-shared')
          return { Component: AdminGate }
        },
        children: [
          {
            path: 'songs',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-songs'))
                .AdminSongsPage,
            }),
          },
          {
            path: 'songs/:songId/edit',
            handle: { preserveAdminDraft: true },
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-song-editor'))
                .AdminSongEditorPage,
            }),
          },
          {
            index: true,
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-overview'))
                .AdminInboxPage,
            }),
          },
          {
            path: 'collection',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-overview'))
                .AdminCollectionPage,
            }),
          },
          {
            path: 'reviews',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-lists'))
                .AdminReviewsPage,
            }),
          },
          {
            path: 'registrations',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-lists'))
                .AdminRegistrationsPage,
            }),
          },
          {
            path: 'special-event-reviews',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-lists'))
                .AdminSpecialPage,
            }),
          },
          {
            path: 'failures',
            lazy: async () => ({
              Component: (await import('@/pages/admin/admin-lists'))
                .AdminFailuresPage,
            }),
          },
        ],
      },
      { path: '/songs', element: <SongCatalogPage /> },
      {
        path: '/songs/:songId',
        lazy: async () => {
          const { SongDetailPage } =
            await import('@/pages/song-detail/song-detail-page')
          return { Component: SongDetailPage }
        },
      },
      {
        path: '/members/:memberId',
        lazy: async () => {
          const { MemberDetailPage } =
            await import('@/pages/member-detail/member-detail-page')
          return { Component: MemberDetailPage }
        },
      },
      {
        path: '/search',
        lazy: async () => {
          const { SearchPage } = await import('@/pages/search/search-page')
          return { Component: SearchPage }
        },
      },
      {
        path: '/members',
        lazy: async () => {
          const { MemberListPage } =
            await import('@/pages/members/member-list-page')
          return { Component: MemberListPage }
        },
      },
      {
        element: <AuthGate />,
        children: [
          {
            path: '/library/playlists/:playlistId/youtube-export',
            lazy: async () => {
              const { YouTubeExportPage } =
                await import('@/pages/youtube-export/youtube-export-page')
              return { Component: YouTubeExportPage }
            },
          },
          {
            path: '/library/youtube',
            lazy: async () => {
              const { YouTubeConnectionPage } =
                await import('@/pages/youtube/youtube-connection-page')
              return { Component: YouTubeConnectionPage }
            },
          },
          {
            path: '/library',
            lazy: async () => {
              const { FavoritesPage } =
                await import('@/pages/favorites/favorites-page')
              return { Component: FavoritesPage }
            },
          },
          {
            path: '/library/playlists',
            lazy: async () => {
              const { PlaylistsPage } =
                await import('@/pages/playlists/playlists-page')
              return { Component: PlaylistsPage }
            },
          },
          {
            path: '/library/playlists/new',
            lazy: async () => {
              const { PlaylistCreatePage } =
                await import('@/pages/playlists/playlist-create-page')
              return { Component: PlaylistCreatePage }
            },
          },
          {
            path: '/library/playlists/:playlistId/edit',
            lazy: async () => {
              const { PlaylistRenamePage } =
                await import('@/pages/playlists/playlist-rename-page')
              return { Component: PlaylistRenamePage }
            },
          },
          {
            path: '/library/playlists/:playlistId',
            lazy: async () => {
              const { PlaylistDetailPage } =
                await import('@/pages/playlists/playlist-detail-page')
              return { Component: PlaylistDetailPage }
            },
          },
        ],
      },
      {
        path: '/account',
        lazy: async () => {
          const { AccountPage } = await import('@/pages/account/account-page')
          return { Component: AccountPage }
        },
      },
      {
        path: '/account/withdrawal',
        lazy: async () => {
          const { WithdrawalPage } =
            await import('@/pages/account/withdrawal-page')
          return { Component: WithdrawalPage }
        },
      },
      {
        path: '/login',
        lazy: async () => {
          const { LoginPage } = await import('@/pages/login/login-page')
          return { Component: LoginPage }
        },
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
