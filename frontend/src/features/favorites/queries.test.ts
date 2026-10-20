import { waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions, endLocalSession } from '@/features/auth/session'
import { apiClient } from '@/shared/api/client'
import { ApiError, ApiResponseError } from '@/shared/api/errors'
import { server } from '@/test/server'
import { getFavoriteState, type FavoritePage } from './api'
import {
  changeFavorite,
  favoriteListKey,
  favoriteListOptions,
  favoriteStateKey,
  favoriteStateOptions,
  updateFavoriteCache,
} from './queries'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const songId = '00000000-0000-4000-8000-000000000010'
const otherId = '00000000-0000-4000-8000-000000000011'
const client = createQueryClient()
const page: FavoritePage = {
  items: [
    {
      songId,
      available: false,
      song: null,
      unavailableMessage: '이용 불가',
      savedAt: '2026-10-20T01:00:00Z',
    },
  ],
  totalCount: 1,
  availableCount: 0,
  hasNext: true,
  nextCursor: 'next',
}
beforeEach(() => {
  client.setQueryData(sessionOptions.queryKey, user)
  apiClient.invalidateCsrf()
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'test' }),
    ),
  )
})
afterEach(() => client.clear())

it('상태 응답의 boolean과 요청한 곡 ID를 검증한다', async () => {
  server.use(
    http.get('*/api/v1/me/favorites/:id', () =>
      HttpResponse.json({ songId, favorited: false }),
    ),
  )
  expect(await getFavoriteState(songId)).toEqual({ songId, favorited: false })
  await expect(getFavoriteState(otherId)).rejects.toBeInstanceOf(
    ApiResponseError,
  )
})

it('목록은 정렬 파라미터 없이 조회하고 이용 불가 항목의 저장 상태도 채운다', async () => {
  server.use(
    http.get('*/api/v1/me/favorites', ({ request }) => {
      const url = new URL(request.url)
      expect(url.searchParams.get('size')).toBe('20')
      expect(url.searchParams.has('sort')).toBe(false)
      return HttpResponse.json(page)
    }),
  )
  await client.fetchInfiniteQuery(favoriteListOptions(user.id))
  expect(client.getQueryData(favoriteStateKey(user.id, songId))).toEqual({
    songId,
    favorited: true,
  })
  const options = favoriteListOptions(user.id)
  expect(options.getNextPageParam?.(page, [page], null, [null])).toBe('next')
  expect(
    options.getNextPageParam?.(page, [page, page], 'next', [null, 'next']),
  ).toBeUndefined()
})

it('해제 후 첫 페이지를 갱신하고 서버 순서가 바뀐 추가 페이지는 폐기한다', () => {
  client.setQueryData(favoriteListKey(user.id), {
    pages: [page, { ...page, items: [{ ...page.items[0]!, songId: otherId }] }],
    pageParams: [null, 'next'],
  })
  updateFavoriteCache(client, user.id, songId, false)
  expect(client.getQueryData(favoriteStateKey(user.id, songId))).toEqual({
    songId,
    favorited: false,
  })
  expect(client.getQueryData(favoriteListKey(user.id))).toMatchObject({
    pages: [{ items: [], totalCount: 0, availableCount: 0 }],
    pageParams: [null],
  })
})

it('계정이 바뀐 요청 결과는 이전 계정 캐시를 복원하지 않는다', async () => {
  await endLocalSession(client)
  updateFavoriteCache(client, user.id, songId, true)
  expect(client.getQueryData(favoriteStateKey(user.id, songId))).toBeUndefined()
  await expect(changeFavorite(client, user.id, songId, true)).rejects.toThrow(
    '계정이 변경',
  )
})

it('목록·상태 조회 401은 세션과 모든 개인 데이터를 비운다', async () => {
  server.use(
    http.get('*/api/v1/me/favorites/:id', () =>
      HttpResponse.json({ code: 'SESSION_EXPIRED' }, { status: 401 }),
    ),
  )
  client.setQueryData(['private', user.id, 'other'], 'secret')
  await client.fetchQuery(favoriteStateOptions(user.id, songId)).catch(() => {})
  await waitFor(() =>
    expect(client.getQueryData(sessionOptions.queryKey)).toBeNull(),
  )
  expect(client.getQueryData(['private', user.id, 'other'])).toBeUndefined()
})

it.each([true, false])(
  '변경 응답 유실은 상태 조회로 확인하고 쓰기를 재전송하지 않는다: %s',
  async (confirmed) => {
    let writes = 0
    server.use(
      http.put('*/api/v1/me/favorites/:id', () => {
        writes++
        return HttpResponse.error()
      }),
      http.get('*/api/v1/me/favorites/:id', () =>
        HttpResponse.json({ songId, favorited: confirmed }),
      ),
    )
    const request = changeFavorite(client, user.id, songId, true)
    if (confirmed) await expect(request).resolves.toBeUndefined()
    else await expect(request).rejects.toBeInstanceOf(Error)
    expect(writes).toBe(1)
  },
)

it.each([403, 404, 409])(
  '확정된 %s 오류는 상태 조회나 자동 쓰기 재시도를 하지 않는다',
  async (status) => {
    let writes = 0
    let reads = 0
    server.use(
      http.put('*/api/v1/me/favorites/:id', () => {
        writes++
        return HttpResponse.json({ code: 'FAVORITE_LIMIT_REACHED' }, { status })
      }),
      http.get('*/api/v1/me/favorites/:id', () => {
        reads++
        return HttpResponse.json({ songId, favorited: false })
      }),
    )
    await expect(
      changeFavorite(client, user.id, songId, true),
    ).rejects.toBeInstanceOf(ApiError)
    expect(writes).toBe(1)
    expect(reads).toBe(0)
  },
)
