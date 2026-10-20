import { afterEach, beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { waitFor } from '@testing-library/react'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import { apiClient } from '@/shared/api/client'
import { server } from '@/test/server'
import {
  addPlaylistSong,
  createPlaylist,
  getPlaylists,
  playlistNameSchema,
  type Playlist,
} from './api'
import {
  playlistKeys,
  playlistListOptions,
  playlistItemsOptions,
  refreshPlaylists,
} from './queries'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const id = '00000000-0000-4000-8000-000000000010'
const song = '00000000-0000-4000-8000-000000000011'
const playlist: Playlist = {
  id,
  name: '내 목록',
  createdAt: '2026-10-20T01:00:00Z',
  updatedAt: '2026-10-20T01:00:00Z',
  totalCount: 1,
  availableCount: 1,
  version: 3,
  containsSong: null,
}
const client = createQueryClient()
beforeEach(() => {
  client.setQueryData(sessionOptions.queryKey, user)
  apiClient.invalidateCsrf()
})
afterEach(() => client.clear())

it('이름은 앞뒤 공백을 제거하고 1~50자로 검증한다', () => {
  expect(playlistNameSchema.parse('  내 목록  ')).toBe('내 목록')
  expect(playlistNameSchema.safeParse('  ').success).toBe(false)
  expect(playlistNameSchema.safeParse('가'.repeat(51)).success).toBe(false)
  expect(playlistNameSchema.parse(' ' + '가'.repeat(50) + ' ')).toHaveLength(50)
})
it('생성과 곡 추가는 이름·곡 ID·조회한 버전을 각각 전송한다', async () => {
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'test' }),
    ),
    http.post('*/api/v1/me/playlists', async ({ request }) => {
      expect(await request.json()).toEqual({ name: '내 목록' })
      return HttpResponse.json(playlist, { status: 201 })
    }),
    http.post('*/api/v1/me/playlists/:id/items', async ({ request }) => {
      expect(await request.json()).toEqual({ songId: song, version: 3 })
      return HttpResponse.json({ itemId: song, playlist })
    }),
  )
  await createPlaylist(' 내 목록 ')
  await addPlaylistSong(playlist, song)
})
it('songId·커서를 유지하고 포함 여부 false도 그대로 받는다', async () => {
  server.use(
    http.get('*/api/v1/me/playlists', ({ request }) => {
      const q = new URL(request.url).searchParams
      expect(q.get('songId')).toBe(song)
      expect(q.get('cursor')).toBe('opaque')
      expect(q.has('sort')).toBe(false)
      return HttpResponse.json({
        items: [{ ...playlist, containsSong: false }],
        totalCount: 1,
        hasNext: false,
        nextCursor: null,
      })
    }),
  )
  expect((await getPlaylists('opaque', song)).items[0]?.containsSong).toBe(
    false,
  )
  expect(playlistKeys.list(user.id)).not.toEqual(
    playlistKeys.list(user.id, song),
  )
})
it('추가한 곡의 포함 여부만 갱신하고 다른 곡의 포함 여부와 계정은 유지한다', async () => {
  for (const target of [undefined, song, id])
    client.setQueryData(playlistKeys.list(user.id, target), {
      pages: [
        {
          items: [{ ...playlist, containsSong: target ? false : null }],
          hasNext: false,
          nextCursor: null,
          totalCount: 1,
        },
      ],
      pageParams: [null],
    })
  await refreshPlaylists(client, user.id, { ...playlist, version: 4 }, song)
  const read = (target?: string) =>
    client.getQueryData<{ pages: { items: Playlist[] }[] }>(
      playlistKeys.list(user.id, target),
    )?.pages[0]?.items[0]
  expect(read(song)?.containsSong).toBe(true)
  expect(read(id)?.containsSong).toBe(false)
  expect(read()?.containsSong).toBeNull()
  client.setQueryData(sessionOptions.queryKey, null)
  await refreshPlaylists(client, user.id, {
    ...playlist,
    name: '다른 계정 결과',
  })
  expect(read()?.name).toBe('내 목록')
})
it('곡 조회 도중 버전이 바뀐 페이지는 합치지 않는다', async () => {
  client.setQueryData(playlistKeys.items(user.id, id), {
    pages: [
      {
        items: [],
        version: 3,
        hasNext: true,
        nextCursor: 'next',
        totalCount: 1,
        availableCount: 1,
      },
    ],
    pageParams: [null],
  })
  server.use(
    http.get('*/api/v1/me/playlists/:id/items', () =>
      HttpResponse.json({
        items: [],
        version: 4,
        hasNext: false,
        nextCursor: null,
        totalCount: 1,
        availableCount: 1,
      }),
    ),
  )
  const options = playlistItemsOptions(user.id, id)
  // Invoke the query function with a next-page cursor to check a concurrent update.
  const queryFn = options.queryFn!
  if (typeof queryFn === 'function')
    await expect(
      queryFn({
        client,
        queryKey: options.queryKey,
        pageParam: 'next',
        signal: new AbortController().signal,
        direction: 'forward',
        meta: undefined,
      }),
    ).rejects.toMatchObject({ code: 'PLAYLIST_CHANGED' })
})
it('목록 401은 개인 캐시와 세션을 제거한다', async () => {
  server.use(
    http.get('*/api/v1/me/playlists', () =>
      HttpResponse.json({ code: 'SESSION_EXPIRED' }, { status: 401 }),
    ),
  )
  client.setQueryData(['private', user.id, 'other'], 'secret')
  await client.fetchInfiniteQuery(playlistListOptions(user.id)).catch(() => {})
  await waitFor(() =>
    expect(client.getQueryData(sessionOptions.queryKey)).toBeNull(),
  )
  expect(client.getQueryData(['private', user.id, 'other'])).toBeUndefined()
})
it('읽기 400은 자동 재시도하지 않는다', async () => {
  let calls = 0
  server.use(
    http.get('*/api/v1/me/playlists', () => {
      calls++
      return HttpResponse.json(
        { code: 'INVALID_PLAYLIST_REQUEST' },
        { status: 400 },
      )
    }),
  )
  await expect(
    client.fetchInfiniteQuery(playlistListOptions(user.id)),
  ).rejects.toBeInstanceOf(ApiError)
  expect(calls).toBe(1)
})
