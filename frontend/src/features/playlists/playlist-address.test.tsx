import { useState } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { createQueryClient } from '@/app/query-client'
import { server } from '@/test/server'
import { PlaylistAddress } from './playlist-address'
import { playlistKeys } from './queries'
import type { Playlist } from './api'

const user = '00000000-0000-4000-8000-000000000800'
const playlist: Playlist = {
  id: '00000000-0000-4000-8000-000000000900',
  publicId: 'a7Kp2mX9qB',
  name: '편집 중인 목록',
  version: 0,
  totalCount: 0,
  availableCount: 0,
  containsSong: null,
  createdAt: '2026-10-20T01:00:00Z',
  updatedAt: '2026-10-20T01:00:00Z',
}
const key = playlistKeys.detail(user, playlist.publicId!)
const clients: ReturnType<typeof createQueryClient>[] = []
afterEach(() => clients.splice(0).forEach((client) => client.clear()))

function Editor() {
  const [draft, setDraft] = useState('저장된 내용')
  return (
    <input
      aria-label="편집 내용"
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
    />
  )
}

function open(cached: boolean) {
  const client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } })
  clients.push(client)
  if (cached) client.setQueryData(key, playlist)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <PlaylistAddress user={user} address={playlist.publicId!}>
          {() => <Editor />}
        </PlaylistAddress>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

it.each(['503', 'network'])(
  '주소의 백그라운드 갱신 %s 오류와 복구에도 저장 전 편집을 유지한다',
  async (kind) => {
    server.use(
      http.get('*/api/v1/me/playlists/by-public-id/:id', () =>
        kind === 'network'
          ? HttpResponse.error()
          : HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 }),
      ),
    )
    const client = open(true)
    await userEvent.clear(screen.getByRole('textbox', { name: '편집 내용' }))
    await userEvent.type(
      screen.getByRole('textbox', { name: '편집 내용' }),
      '저장 전 변경',
    )
    await act(async () => {
      await client.invalidateQueries({ queryKey: key, exact: true })
    })
    await waitFor(() => expect(client.getQueryState(key)?.status).toBe('error'))
    expect(client.getQueryData(key)).toEqual(playlist)
    expect(screen.getByRole('textbox', { name: '편집 내용' })).toHaveValue(
      '저장 전 변경',
    )
    server.use(
      http.get('*/api/v1/me/playlists/by-public-id/:id', () =>
        HttpResponse.json(playlist),
      ),
    )
    await act(async () => {
      await client.invalidateQueries({ queryKey: key, exact: true })
    })
    await waitFor(() =>
      expect(client.getQueryState(key)?.status).toBe('success'),
    )
    expect(screen.getByRole('textbox', { name: '편집 내용' })).toHaveValue(
      '저장 전 변경',
    )
  },
)

it.each([403, 404])(
  '기존 주소 데이터가 있어도 %i 응답에는 편집 내용을 숨긴다',
  async (status) => {
    server.use(
      http.get('*/api/v1/me/playlists/by-public-id/:id', () =>
        HttpResponse.json({ code: 'PLAYLIST_NOT_FOUND' }, { status }),
      ),
    )
    const client = open(true)
    await userEvent.type(
      screen.getByRole('textbox', { name: '편집 내용' }),
      ' 비공개 편집',
    )
    await act(async () => {
      await client.invalidateQueries({ queryKey: key, exact: true })
    })
    expect(
      await screen.findByText('이 목록에 접근할 수 없습니다'),
    ).toBeVisible()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  },
)

it('최초 주소 조회 실패는 편집기를 열지 않고 재시도 성공 후 연다', async () => {
  server.use(
    http.get('*/api/v1/me/playlists/by-public-id/:id', () =>
      HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 }),
    ),
  )
  open(false)
  expect(await screen.findByText('불러오지 못했어요')).toBeVisible()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  server.use(
    http.get('*/api/v1/me/playlists/by-public-id/:id', () =>
      HttpResponse.json(playlist),
    ),
  )
  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('textbox', { name: '편집 내용' })).toHaveValue(
    '저장된 내용',
  )
})
