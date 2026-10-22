import { beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import {
  changeExport,
  createExport,
  getExport,
  getActiveExport,
  type ExportJob,
} from './api'

const playlist = '00000000-0000-4000-8000-000000000900'
const id = '00000000-0000-4000-8000-000000000901'
const requestId = '00000000-0000-4000-8000-000000000902'
const job: ExportJob = {
  id,
  playlistId: playlist,
  version: 3,
  status: 'QUEUED',
  totalCount: 3,
  copiedCount: 0,
  skippedCount: 1,
  remainingCount: 2,
  youtubePlaylistId: null,
  youtubeUrl: null,
  errorCode: null,
  createdAt: '2026-10-22T00:00:00Z',
  updatedAt: '2026-10-22T00:00:00Z',
}

beforeEach(() => {
  apiClient.invalidateCsrf()
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'export-csrf' }),
    ),
  )
})
it('기존 활성 작업 조회는 CSRF·변경 요청 없이 원본 목록과 버전을 반환한다', async () => {
  server.use(
    http.get('*/api/v1/me/youtube-exports/active', ({ request }) => {
      expect(request.headers.has('X-CSRF-TOKEN')).toBe(false)
      return HttpResponse.json({ job })
    }),
  )
  expect(await getActiveExport()).toEqual({ job })
})
it('활성 작업 없음은 명시적인 job:null만 인정한다', async () => {
  server.use(
    http.get('*/api/v1/me/youtube-exports/active', () =>
      HttpResponse.json({ job: null }),
    ),
  )
  expect(await getActiveExport()).toEqual({ job: null })
})
it.each([
  {},
  null,
  { job: undefined },
  { job: { ...job, status: 'SUCCEEDED' } },
  { job: { ...job, status: 'CANCELLED' } },
  { job: { ...job, copiedCount: -1 } },
  { job: { ...job, playlistId: 'wrong' } },
  {
    job: {
      ...job,
      youtubePlaylistId: 'PLtest',
      youtubeUrl: 'https://evil.test/playlist?list=PLtest',
    },
  },
])('잘못된 활성 작업 응답을 없음으로 취급하지 않는다: %j', async (body) => {
  server.use(
    http.get('*/api/v1/me/youtube-exports/active', () =>
      HttpResponse.json(body),
    ),
  )
  await expect(getActiveExport()).rejects.toBeInstanceOf(ApiResponseError)
})
it('기존 작업 조회 실패는 작업 없음과 구분한다', async () => {
  server.use(
    http.get('*/api/v1/me/youtube-exports/active', () =>
      HttpResponse.json({ code: 'EXPORT_LOOKUP_UNAVAILABLE' }, { status: 503 }),
    ),
  )
  await expect(getActiveExport()).rejects.toMatchObject({
    status: 503,
    code: 'EXPORT_LOOKUP_UNAVAILABLE',
  })
})
it('접수 응답을 다시 확인할 때 같은 requestId·version을 사용한다', async () => {
  const bodies: unknown[] = []
  server.use(
    http.post(
      `*/api/v1/me/playlists/${playlist}/youtube-exports`,
      async ({ request }) => {
        expect(request.headers.get('X-CSRF-TOKEN')).toBe('export-csrf')
        bodies.push(await request.json())
        return HttpResponse.json(job, { status: 202 })
      },
    ),
  )
  expect(await createExport(playlist, { requestId, version: 3 })).toEqual(job)
  await createExport(playlist, { requestId, version: 3 })
  expect(bodies).toEqual([
    { requestId, version: 3 },
    { requestId, version: 3 },
  ])
})
it('재시도·취소는 새 접수 대신 기존 작업 API와 CSRF를 사용한다', async () => {
  const writes: string[] = []
  for (const action of ['retry', 'cancel'] as const)
    server.use(
      http.post(
        `*/api/v1/me/youtube-exports/${id}/${action}`,
        ({ request }) => {
          expect(request.headers.get('X-CSRF-TOKEN')).toBe('export-csrf')
          writes.push(action)
          return HttpResponse.json({
            ...job,
            status: action === 'cancel' ? 'CANCELLED' : 'QUEUED',
          })
        },
      ),
    )
  await changeExport(playlist, id, 'retry')
  expect((await changeExport(playlist, id, 'cancel')).status).toBe('CANCELLED')
  expect(writes).toEqual(['retry', 'cancel'])
})
it.each([
  { playlistId: requestId },
  { id: requestId },
  { status: 'PARTIAL' },
  { remainingCount: 0 },
  { copiedCount: -1 },
  { totalCount: 501 },
  { version: -1 },
  {
    youtubeUrl: 'https://evil.test/playlist?list=PLtest',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'http://www.youtube.com/playlist?list=PLtest',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'https://user@www.youtube.com/playlist?list=PLtest',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'https://www.youtube.com/playlist?list=wrong',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'https://www.youtube.com/playlist?list=PLtest&list=PLother',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'https://www.youtube.com/watch?v=test',
    youtubePlaylistId: 'PLtest',
  },
  {
    youtubeUrl: 'https://www.youtube.com/playlist?list=PLtest',
    youtubePlaylistId: null,
  },
])(
  '작업·원본·곡 수·결과 주소가 계약과 다르면 사용하지 않는다: %j',
  async (override) => {
    server.use(
      http.get(`*/api/v1/me/youtube-exports/${id}`, () =>
        HttpResponse.json({ ...job, ...override }),
      ),
    )
    await expect(getExport(playlist, id)).rejects.toBeInstanceOf(
      ApiResponseError,
    )
  },
)
it('결과 URL이 없으면 임의로 생성하지 않으며 반환된 정상 주소는 유지한다', async () => {
  server.use(
    http.get(`*/api/v1/me/youtube-exports/${id}`, () => HttpResponse.json(job)),
  )
  expect((await getExport(playlist, id)).youtubeUrl).toBeNull()
  const youtubeUrl = 'https://www.youtube.com/playlist?list=PLtest'
  server.use(
    http.get(`*/api/v1/me/youtube-exports/${id}`, () =>
      HttpResponse.json({ ...job, youtubePlaylistId: 'PLtest', youtubeUrl }),
    ),
  )
  expect((await getExport(playlist, id)).youtubeUrl).toBe(youtubeUrl)
})
it('접수 요청과 다른 버전의 작업 응답은 사용하지 않는다', async () => {
  server.use(
    http.post(`*/api/v1/me/playlists/${playlist}/youtube-exports`, () =>
      HttpResponse.json({ ...job, version: 4 }, { status: 202 }),
    ),
  )
  await expect(
    createExport(playlist, { requestId, version: 3 }),
  ).rejects.toBeInstanceOf(ApiResponseError)
})
