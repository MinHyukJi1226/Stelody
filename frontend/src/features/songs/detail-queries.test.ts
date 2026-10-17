import { it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { createQueryClient } from '@/app/query-client'
import { songDetailOptions, songViewsOptions } from './detail-queries'

const id = '00000000-0000-4000-8000-000000000001'
it('곡 상세·기간별 조회수를 독립된 API와 캐시로 조회한다', async () => {
  const calls: string[] = []
  server.use(
    http.get('*/api/v1/songs/:id', ({ request, params }) => {
      calls.push(new URL(request.url).pathname)
      return HttpResponse.json({ song: { id: params.id } })
    }),
    http.get('*/api/v1/songs/:id/views', ({ request }) => {
      const url = new URL(request.url)
      calls.push(url.pathname + url.search)
      return HttpResponse.json({
        latestViewCount: 0,
        points: [{ date: '2026-10-16', viewCount: null }],
      })
    }),
  )
  const client = createQueryClient()
  try {
    expect((await client.fetchQuery(songDetailOptions(id))).song.id).toBe(id)
    const data = await client.fetchQuery(songViewsOptions(id, 'video-a', 7))
    expect(data.latestViewCount).toBe(0)
    expect(data.points[0]?.viewCount).toBeNull()
    await client.fetchQuery(songViewsOptions(id, 'video-a', 30))
    await client.fetchQuery(songViewsOptions(id, 'video-b', 7))
    expect(calls).toEqual([
      `/api/v1/songs/${id}`,
      `/api/v1/songs/${id}/views?days=7`,
      `/api/v1/songs/${id}/views?days=30`,
      `/api/v1/songs/${id}/views?days=7`,
    ])
  } finally {
    client.clear()
  }
})
it('곡 상세 조회를 취소하면 서버 요청에 중단 신호를 전달한다', async () => {
  let started!: () => void
  const requested = new Promise<void>((resolve) => {
    started = resolve
  })
  let aborted!: () => void
  const cancelled = new Promise<void>((resolve) => {
    aborted = resolve
  })
  server.use(
    http.get('*/api/v1/songs/:id', async ({ request }) => {
      request.signal.addEventListener('abort', aborted, { once: true })
      started()
      await cancelled
      return HttpResponse.json({})
    }),
  )
  const client = createQueryClient()
  try {
    const options = songDetailOptions(id)
    const fetch = client.fetchQuery(options).catch(() => undefined)
    await requested
    await client.cancelQueries({ queryKey: options.queryKey, exact: true })
    await cancelled
    await fetch
  } finally {
    client.clear()
  }
})
