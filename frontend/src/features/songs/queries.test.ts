import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { createQueryClient } from '@/app/query-client'
import { getSongs } from './api'
import { latestSongsOptions, songCatalogOptions, uniqueSongs } from './queries'
import type { SongPage } from '@/shared/api/types'

const emptyPage: SongPage = {
  items: [],
  nextCursor: null,
  hasNext: false,
  totalCount: 0,
}

describe('공개 곡 조회', () => {
  it('검색어와 불투명 커서를 인코딩하고 배열·false 값을 보존한다', async () => {
    server.use(
      http.get('*/api/v1/songs', ({ request }) => {
        const q = new URL(request.url).searchParams
        expect(q.get('q')).toBe('노래 & cover?')
        expect(q.get('cursor')).toBe('opaque+/=&cursor')
        expect(q.getAll('memberIds')).toEqual(['member-a', 'member-b'])
        expect(q.get('collaboration')).toBe('false')
        expect(q.has('year')).toBe(false)
        return HttpResponse.json(emptyPage)
      }),
    )
    await expect(
      getSongs({
        q: '노래 & cover?',
        cursor: 'opaque+/=&cursor',
        memberIds: ['member-a', 'member-b'],
        collaboration: false,
      }),
    ).resolves.toEqual(emptyPage)
  })

  it('홈 최신곡은 검색어 없이 실제 공개일 최신순으로 요청한다', async () => {
    server.use(
      http.get('*/api/v1/songs', ({ request }) => {
        const q = new URL(request.url).searchParams
        expect(Object.fromEntries(q)).toEqual({
          mode: 'BROWSE',
          sort: 'LATEST',
          size: '4',
        })
        return HttpResponse.json(emptyPage)
      }),
    )
    const client = createQueryClient()
    await expect(client.fetchQuery(latestSongsOptions)).resolves.toEqual(
      emptyPage,
    )
    client.clear()
  })

  it('다음 커서를 그대로 전달하고 마지막 페이지에서 조회를 끝낸다', async () => {
    const cursors: (string | null)[] = []
    server.use(
      http.get('*/api/v1/songs', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        cursors.push(cursor)
        return HttpResponse.json(
          cursor
            ? emptyPage
            : { ...emptyPage, hasNext: true, nextCursor: 'next+/=' },
        )
      }),
    )
    const client = createQueryClient()
    const result = await client.fetchInfiniteQuery({
      ...songCatalogOptions,
      pages: 3,
    })
    expect(cursors).toEqual([null, 'next+/='])
    expect(result.pages).toHaveLength(2)
    client.clear()
  })

  it('서버가 같은 커서를 반복해도 무한히 페이지를 요청하지 않는다', async () => {
    let calls = 0
    server.use(
      http.get('*/api/v1/songs', () => {
        calls++
        return HttpResponse.json({
          ...emptyPage,
          hasNext: true,
          nextCursor: 'same',
        })
      }),
    )
    const client = createQueryClient()
    await client.fetchInfiniteQuery({ ...songCatalogOptions, pages: 5 })
    expect(calls).toBe(2)
    client.clear()
  })

  it('잘못된 커서 오류를 재요청하지 않고 원래 코드를 전달한다', async () => {
    let calls = 0
    server.use(
      http.get('*/api/v1/songs', () => {
        calls++
        return HttpResponse.json(
          { code: 'INVALID_CATALOG_QUERY', title: '검색 조건을 확인해 주세요' },
          { status: 400 },
        )
      }),
    )
    const client = createQueryClient()
    await expect(client.fetchQuery(latestSongsOptions)).rejects.toMatchObject({
      code: 'INVALID_CATALOG_QUERY',
      status: 400,
    })
    expect(calls).toBe(1)
    client.clear()
  })

  it('여러 페이지의 중복 곡을 제거하되 서버에서 받은 순서를 유지한다', () => {
    // This helper only reads IDs; minimal fixtures keep the assertion focused.
    const a = { id: 'a' } as SongPage['items'][number]
    const b = { id: 'b' } as SongPage['items'][number]
    const c = { id: 'c' } as SongPage['items'][number]
    expect(uniqueSongs([{ items: [a, b] }, { items: [b, c, a] }])).toEqual([
      a,
      b,
      c,
    ])
  })
})
