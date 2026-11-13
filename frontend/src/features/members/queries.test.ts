import { expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { createQueryClient } from '@/app/query-client'
import {
  homeMembersOptions,
  memberDetailOptions,
  memberSongsOptions,
} from './queries'
import { defaultMemberSongFilters } from './song-filters'

const id = '00000000-0000-4000-8000-000000000101'
const empty = { items: [], hasNext: false, nextCursor: null, totalCount: 0 }

it('멤버 목록은 졸업 멤버를 포함하고 커서와 서버 순서를 유지한다', async () => {
  const cursors: (string | null)[] = []
  server.use(
    http.get('*/api/v1/members', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('status')).toBe('ALL')
      expect(params.get('size')).toBe('50')
      cursors.push(params.get('cursor'))
      return HttpResponse.json(
        params.has('cursor')
          ? empty
          : {
              items: [{ id, name: '멤버' }],
              hasNext: true,
              nextCursor: 'member+/=&cursor',
            },
      )
    }),
  )
  const client = createQueryClient()
  try {
    const result = await client.fetchInfiniteQuery({
      ...homeMembersOptions,
      pages: 3,
    })
    expect(cursors).toEqual([null, 'member+/=&cursor'])
    expect(result.pages[0]?.items[0]?.id).toBe(id)
  } finally {
    client.clear()
  }
})

it('멤버·필터별 캐시를 분리하고 추가 페이지에도 같은 조건을 보낸다', async () => {
  const requests: URL[] = []
  server.use(
    http.get('*/api/v1/members/:id', ({ params }) =>
      HttpResponse.json({ member: { id: params.id }, channels: [] }),
    ),
    http.get('*/api/v1/members/:id/songs', ({ request }) => {
      const url = new URL(request.url)
      expect(url.searchParams.has('collaboration')).toBe(false)
      requests.push(url)
      return HttpResponse.json(
        url.searchParams.has('cursor')
          ? empty
          : { ...empty, hasNext: true, nextCursor: 'song+/=&cursor' },
      )
    }),
  )
  const client = createQueryClient()
  try {
    expect((await client.fetchQuery(memberDetailOptions(id))).member.id).toBe(
      id,
    )
    const filters = {
      type: 'COVER',
      multipleParticipants: true,
      sort: 'VIEWS',
    } as const
    await client.fetchInfiniteQuery({
      ...memberSongsOptions(id, filters),
      pages: 3,
    })
    expect(requests.map((url) => Object.fromEntries(url.searchParams))).toEqual(
      [
        {
          type: 'COVER',
          participation: 'MULTIPLE',
          sort: 'VIEWS',
          size: '20',
        },
        {
          type: 'COVER',
          participation: 'MULTIPLE',
          sort: 'VIEWS',
          size: '20',
          cursor: 'song+/=&cursor',
        },
      ],
    )
    await client.fetchInfiniteQuery(
      memberSongsOptions(id, defaultMemberSongFilters),
    )
    await client.fetchInfiniteQuery(
      memberSongsOptions(id.replace(/101$/, '102'), filters),
    )
    expect(requests.at(-2)?.searchParams.get('cursor')).toBeNull()
    expect(requests.at(-2)?.searchParams.get('participation')).toBe('ALL')
    expect(requests.at(-1)?.pathname).toBe(
      `/api/v1/members/${id.replace(/101$/, '102')}/songs`,
    )
  } finally {
    client.clear()
  }
})

it('멤버 곡 목록이 같은 커서를 반복해도 추가 요청을 끝낸다', async () => {
  let calls = 0
  server.use(
    http.get('*/api/v1/members/:id/songs', () => {
      calls++
      return HttpResponse.json({ ...empty, hasNext: true, nextCursor: 'same' })
    }),
  )
  const client = createQueryClient()
  try {
    await client.fetchInfiniteQuery({
      ...memberSongsOptions(id, defaultMemberSongFilters),
      pages: 5,
    })
    expect(calls).toBe(2)
  } finally {
    client.clear()
  }
})

it('멤버 곡 요청을 취소하면 서버 요청에 중단 신호를 전달한다', async () => {
  let start!: () => void
  let abort!: () => void
  const started = new Promise<void>((resolve) => {
    start = resolve
  })
  const aborted = new Promise<void>((resolve) => {
    abort = resolve
  })
  server.use(
    http.get('*/api/v1/members/:id/songs', async ({ request }) => {
      request.signal.addEventListener('abort', abort, { once: true })
      start()
      await aborted
      return HttpResponse.json(empty)
    }),
  )
  const client = createQueryClient()
  try {
    const options = memberSongsOptions(id, defaultMemberSongFilters)
    const request = client.fetchInfiniteQuery(options).catch(() => undefined)
    await started
    await client.cancelQueries({ queryKey: options.queryKey, exact: true })
    await aborted
    await request
  } finally {
    client.clear()
  }
})

it('개인곡 조회는 기존 불리언을 보내지 않고 같은 조건으로 커서를 이어간다', async () => {
  const requests: URL[] = []
  server.use(
    http.get('*/api/v1/members/:id/songs', ({ request }) => {
      const url = new URL(request.url)
      requests.push(url)
      return HttpResponse.json({
        items: [],
        totalCount: 0,
        hasNext: !url.searchParams.has('cursor'),
        nextCursor: url.searchParams.has('cursor') ? null : 'solo-next',
      })
    }),
  )
  const client = createQueryClient()
  try {
    await client.fetchInfiniteQuery({
      ...memberSongsOptions('00000000-0000-4000-8000-000000000101', {
        type: 'COVER',
        sort: 'LATEST',
        multipleParticipants: false,
        participation: 'SOLO',
      }),
      pages: 2,
    })
    expect(requests).toHaveLength(2)
    for (const url of requests) {
      expect(url.searchParams.get('participation')).toBe('SOLO')
      expect(url.searchParams.has('multipleParticipants')).toBe(false)
      expect(url.searchParams.has('collaboration')).toBe(false)
    }
    expect(requests.map((url) => url.searchParams.get('cursor'))).toEqual([
      null,
      'solo-next',
    ])
  } finally {
    client.clear()
  }
})
