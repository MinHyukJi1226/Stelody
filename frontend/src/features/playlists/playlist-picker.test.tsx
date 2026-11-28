import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { http, HttpResponse } from 'msw'
import { expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions } from '@/features/auth/session'
import { apiClient } from '@/shared/api/client'
import { ToastProvider } from '@/shared/ui/toast-provider'
import { server } from '@/test/server'
import { PlaylistPicker } from './playlist-picker'
import type { Playlist } from './api'

vi.mock('@/shared/ui/dialog', () => ({
  Dialog: ({ children }: { children: ReactNode }) => (
    <div role="dialog">{children}</div>
  ),
}))

const account = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const songId = '00000000-0000-4000-8000-000000000010'
const playlists: Playlist[] = ['첫 번째 목록', '두 번째 목록'].map(
  (name, index) => ({
    id: `00000000-0000-4000-8000-00000000010${index}`,
    name,
    version: 1,
    totalCount: 0,
    availableCount: 0,
    containsSong: false,
    createdAt: '2026-11-29T00:00:00Z',
    updatedAt: '2026-11-29T00:00:00Z',
  }),
)

it.each([0, 1])(
  '%i번 목록에 추가하는 동안 해당 버튼만 저장 중이며 중복 요청을 막고 실패 후 복구한다',
  async (index) => {
    const client = createQueryClient()
    client.setQueryData(sessionOptions.queryKey, account)
    apiClient.invalidateCsrf()
    const onClose = vi.fn()
    const requests: string[] = []
    let finish = () => {}
    const response = new Promise<void>((resolve) => {
      finish = resolve
    })
    server.use(
      http.get('*/api/v1/auth/csrf', () =>
        HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'picker-test' }),
      ),
      http.get('*/api/v1/me/playlists', () =>
        HttpResponse.json({
          items: playlists,
          totalCount: 2,
          hasNext: false,
          nextCursor: null,
        }),
      ),
      http.post('*/api/v1/me/playlists/:id/items', async ({ params }) => {
        requests.push(String(params.id))
        await response
        return HttpResponse.json(
          { code: 'PLAYLIST_ITEM_LIMIT_REACHED' },
          { status: 409 },
        )
      }),
    )
    const view = render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <ToastProvider>
            <PlaylistPicker
              userId={account.id}
              songId={songId}
              returnTo="/songs/test"
              onClose={onClose}
            />
          </ToastProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    )
    try {
      const user = userEvent.setup()
      const target = await screen.findByRole('button', {
        name: `${playlists[index]!.name} 추가하기`,
      })
      const other = screen.getByRole('button', {
        name: `${playlists[1 - index]!.name} 추가하기`,
      })
      await waitFor(() => expect(target).toBeEnabled())
      await user.click(target)
      await waitFor(() => {
        expect(requests).toEqual([playlists[index]!.id])
        expect(target).toHaveTextContent('저장 중…')
      })
      expect(screen.getAllByText('저장 중…')).toHaveLength(1)
      expect(target).toHaveAttribute('aria-busy', 'true')
      expect(other).toHaveTextContent('추가하기')
      expect(other).toHaveAttribute('aria-busy', 'false')
      expect(other).toBeDisabled()
      await user.click(other)
      await user.click(target)
      expect(requests).toHaveLength(1)
      finish()
      await waitFor(() => expect(target).toBeEnabled())
      expect(target).toHaveTextContent('추가하기')
      expect(other).toBeEnabled()
      expect(screen.queryByText('저장 중…')).not.toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent(
        '이 목록에 담을 수 있는 곡 수를 초과했어요.',
      )
      expect(onClose).not.toHaveBeenCalled()
    } finally {
      finish()
      view.unmount()
      client.clear()
    }
  },
)
