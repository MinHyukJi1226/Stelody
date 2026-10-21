import { afterEach, beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiClient } from '@/shared/api/client'
import { ApiError, ApiResponseError } from '@/shared/api/errors'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions } from '@/features/auth/session'
import {
  deletePlaylist,
  removePlaylistItem,
  renamePlaylist,
  savePlaylistOrder,
  type Playlist,
  type PlaylistItem,
} from './api'
import { getPlaylistSnapshot } from './editor-snapshot'
import { playlistKeys, refreshPlaylists } from './queries'

const id = '00000000-0000-4000-8000-000000000900'
const song = '00000000-0000-4000-8000-000000000001'
const user = '00000000-0000-4000-8000-000000000800'
const currentUser = {
  id: user,
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const playlist: Playlist = {
  id,
  name: '내 목록',
  version: 3,
  totalCount: 2,
  availableCount: 0,
  containsSong: null,
  createdAt: '2026-10-20T01:00:00Z',
  updatedAt: '2026-10-20T01:00:00Z',
}
const item = (position: number): PlaylistItem => ({
  id: `00000000-0000-4000-8000-00000000095${position}`,
  songId: song,
  position,
  song: null,
  available: false,
  unavailableMessage: '현재 이용할 수 없는 곡',
  addedAt: '2026-10-20T01:00:00Z',
})
const client = createQueryClient()
beforeEach(() => {
  apiClient.invalidateCsrf()
  client.setQueryData(sessionOptions.queryKey, currentUser)
})
afterEach(() => client.clear())

it('이름 수정·곡 제거·순서 저장·삭제는 조회 버전과 항목 ID를 정확한 위치에 전달한다', async () => {
  const requests: unknown[] = []
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }),
    ),
    http.patch('*/api/v1/me/playlists/:id', async ({ request, params }) => {
      requests.push([
        'rename',
        params.id,
        await request.json(),
        request.headers.get('X-CSRF-TOKEN'),
      ])
      return HttpResponse.json(playlist)
    }),
    http.delete(
      '*/api/v1/me/playlists/:id/items/:itemId',
      ({ request, params }) => {
        requests.push([
          'remove',
          params.itemId,
          new URL(request.url).searchParams.get('version'),
        ])
        return HttpResponse.json(playlist)
      },
    ),
    http.put('*/api/v1/me/playlists/:id/order', async ({ request }) => {
      requests.push(['order', await request.json()])
      return HttpResponse.json(playlist)
    }),
    http.delete('*/api/v1/me/playlists/:id', ({ request }) => {
      requests.push([
        'delete',
        new URL(request.url).searchParams.get('version'),
      ])
      return new HttpResponse(null, { status: 204 })
    }),
  )
  await renamePlaylist(playlist, '  새 이름 🎵  ')
  await removePlaylistItem(playlist, item(0).id)
  await savePlaylistOrder(playlist, [item(1).id, item(0).id])
  await deletePlaylist(playlist)
  expect(requests).toEqual([
    ['rename', id, { name: '새 이름 🎵', version: 3 }, 'token'],
    ['remove', item(0).id, '3'],
    ['order', { itemIds: [item(1).id, item(0).id], version: 3 }],
    ['delete', '3'],
  ])
})

it('전체 순서 조회는 같은 버전의 모든 페이지에서 이용 불가 항목까지 모은다', async () => {
  const cursors: (string | null)[] = []
  server.use(
    http.get('*/api/v1/me/playlists/:id', () => HttpResponse.json(playlist)),
    http.get('*/api/v1/me/playlists/:id/items', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor')
      cursors.push(cursor)
      return HttpResponse.json({
        version: 3,
        totalCount: 2,
        availableCount: 0,
        items: [item(cursor ? 1 : 0)],
        hasNext: !cursor,
        nextCursor: cursor ? null : 'opaque',
      })
    }),
  )
  const result = await getPlaylistSnapshot(id)
  expect(cursors).toEqual([null, 'opaque'])
  expect(result.items.map((i) => i.id)).toEqual([item(0).id, item(1).id])
  expect(result.items.every((i) => i.song === null)).toBe(true)
})

it.each(['version', 'missing', 'duplicate', 'cursor'] as const)(
  '전체 조회가 불완전하면 편집용 결과를 반환하지 않는다: %s',
  async (kind) => {
    server.use(
      http.get('*/api/v1/me/playlists/:id', () => HttpResponse.json(playlist)),
      http.get('*/api/v1/me/playlists/:id/items', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        return HttpResponse.json({
          version: kind === 'version' ? 4 : 3,
          totalCount: 2,
          availableCount: 0,
          items:
            kind === 'duplicate' ? [item(0), item(0)] : [item(cursor ? 1 : 0)],
          hasNext: kind === 'cursor',
          nextCursor: kind === 'cursor' ? 'same' : null,
        })
      }),
    )
    await expect(getPlaylistSnapshot(id)).rejects.toBeInstanceOf(
      kind === 'version' ? ApiError : ApiResponseError,
    )
  },
)

it('곡 제거 후에는 그 곡의 포함 여부만 false로 바꾼다', async () => {
  for (const target of [song, id])
    client.setQueryData(playlistKeys.list(user, target), {
      pages: [
        {
          items: [{ ...playlist, containsSong: true }],
          totalCount: 1,
          hasNext: false,
          nextCursor: null,
        },
      ],
      pageParams: [null],
    })
  await refreshPlaylists(
    client,
    user,
    { ...playlist, totalCount: 1, version: 4 },
    song,
    false,
  )
  const read = (target: string) =>
    client.getQueryData<{ pages: { items: Playlist[] }[] }>(
      playlistKeys.list(user, target),
    )?.pages[0]?.items[0]
  expect(read(song)?.containsSong).toBe(false)
  expect(read(id)?.containsSong).toBe(true)
})
