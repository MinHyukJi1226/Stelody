import { beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import {
  authorizeYouTube,
  disconnectYouTube,
  getYouTubeConnection,
} from './api'
import {
  cleanYouTubeReturn,
  readYouTubeResult,
  readYouTubeFailureCode,
} from './oauth-result'

it.each([
  [
    'youtubeResult=failed&youtubeError=YOUTUBE_SCOPE_REQUIRED',
    'YOUTUBE_SCOPE_REQUIRED',
  ],
  [
    'youtubeResult=failed&youtubeError=YOUTUBE_OFFLINE_ACCESS_REQUIRED',
    'YOUTUBE_OFFLINE_ACCESS_REQUIRED',
  ],
  ['youtubeResult=failed&youtubeError=PRIVATE_VALUE', undefined],
  [
    'youtubeResult=success&youtubeError=YOUTUBE_SCOPE_REQUIRED',
    'YOUTUBE_SCOPE_REQUIRED',
  ],
  ['youtubeResult=cancelled&youtubeError=YOUTUBE_SCOPE_REQUIRED', undefined],
  [
    'youtubeResult=failed&youtubeError=YOUTUBE_SCOPE_REQUIRED&youtubeError=PRIVATE_VALUE',
    undefined,
  ],
] as const)(
  '개발 진단에는 허용된 실패 코드만 사용한다: %s',
  (query, expected) => {
    expect(readYouTubeFailureCode(new URLSearchParams(query))).toBe(expected)
  },
)

beforeEach(() => {
  apiClient.invalidateCsrf()
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }),
    ),
  )
})

it('상태 조회·추가 동의·해제는 같은 API와 CSRF 계약을 사용한다', async () => {
  const writes: unknown[] = []
  server.use(
    http.get('*/api/v1/me/youtube/connection', () =>
      HttpResponse.json({ status: 'DISCONNECTED' }),
    ),
    http.post('*/api/v1/me/youtube/authorizations', async ({ request }) => {
      writes.push([
        request.method,
        request.headers.get('X-CSRF-TOKEN'),
        await request.json(),
      ])
      return HttpResponse.json({
        authorizationUrl:
          'https://accounts.google.com/o/oauth2/v2/auth?state=test',
        expiresAt: '2100-01-01T00:00:00Z',
      })
    }),
    http.delete('*/api/v1/me/youtube/connection', ({ request }) => {
      writes.push([request.method, request.headers.get('X-CSRF-TOKEN')])
      return HttpResponse.json({ status: 'REVOKING' })
    }),
  )
  expect(await getYouTubeConnection()).toEqual({ status: 'DISCONNECTED' })
  await authorizeYouTube()
  expect(await disconnectYouTube()).toEqual({ status: 'REVOKING' })
  expect(writes).toEqual([
    ['POST', 'csrf', { returnTo: '/library/youtube', callbackClient: 'local' }],
    ['DELETE', 'csrf'],
  ])
})

it('추가 동의 후 원래 내보내기 작업으로 돌아올 경로를 유지한다', async () => {
  const returnTo =
    '/library/youtube?returnTo=%2Flibrary%2Fplaylists%2Ftest%2Fyoutube-export%3FjobId%3Dtest'
  let body: unknown
  server.use(
    http.post('*/api/v1/me/youtube/authorizations', async ({ request }) => {
      body = await request.json()
      return HttpResponse.json({
        authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        expiresAt: '2100-01-01T00:00:00Z',
      })
    }),
  )
  await authorizeYouTube(returnTo)
  expect(body).toEqual({ returnTo, callbackClient: 'local' })
})

it.each([
  'DISABLED',
  'DISCONNECTED',
  'CONNECTED',
  'RECONNECT_REQUIRED',
  'REVOKING',
])('연결 상태 %s를 원문 계약대로 읽는다', async (status) => {
  server.use(
    http.get('*/api/v1/me/youtube/connection', () =>
      HttpResponse.json({ status }),
    ),
  )
  expect(await getYouTubeConnection()).toEqual({ status })
})
it('알 수 없는 상태를 미연결로 간주하지 않는다', async () => {
  server.use(
    http.get('*/api/v1/me/youtube/connection', () =>
      HttpResponse.json({ status: 'UNKNOWN' }),
    ),
  )
  await expect(getYouTubeConnection()).rejects.toBeInstanceOf(ApiResponseError)
})
it.each([
  'javascript:alert(1)',
  'http://accounts.google.com/o/oauth2/v2/auth',
  'https://accounts.google.com.evil.test/o/oauth2/v2/auth',
  'https://user@accounts.google.com/o/oauth2/v2/auth',
  'https://accounts.google.com/o/oauth2/v2/auth#token',
  'https://accounts.google.com/other',
])(
  '예상하지 못한 인증 주소로 이동하지 않는다: %s',
  async (authorizationUrl) => {
    server.use(
      http.post('*/api/v1/me/youtube/authorizations', () =>
        HttpResponse.json({
          authorizationUrl,
          expiresAt: '2100-01-01T00:00:00Z',
        }),
      ),
    )
    await expect(authorizeYouTube()).rejects.toBeInstanceOf(ApiResponseError)
  },
)
it('이미 만료된 추가 동의 주소를 사용하지 않는다', async () => {
  server.use(
    http.post('*/api/v1/me/youtube/authorizations', () =>
      HttpResponse.json({
        authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        expiresAt: '2000-01-01T00:00:00Z',
      }),
    ),
  )
  await expect(authorizeYouTube()).rejects.toBeInstanceOf(ApiResponseError)
})
it('해제 응답이 연결 상태면 성공으로 처리하지 않는다', async () => {
  server.use(
    http.delete('*/api/v1/me/youtube/connection', () =>
      HttpResponse.json({ status: 'CONNECTED' }),
    ),
  )
  await expect(disconnectYouTube()).rejects.toBeInstanceOf(ApiResponseError)
})
it.each([
  ['', null],
  ['youtubeResult=success', 'success'],
  ['youtubeResult=cancelled&youtubeError=YOUTUBE_CONSENT_DENIED', 'cancelled'],
  ['youtubeResult=failed&youtubeError=SESSION_EXPIRED', 'failed'],
  ['youtubeResult=other', 'failed'],
  ['youtubeResult=success&youtubeResult=failed', 'failed'],
  ['youtubeResult=success&youtubeError=unexpected', 'failed'],
  ['youtubeError=unknown', 'failed'],
] as const)('인증 결과는 안내용 값으로만 읽는다: %s', (query, expected) => {
  expect(readYouTubeResult(new URLSearchParams(query))).toBe(expected)
})
it('결과·인증 파라미터를 모두 지우고 다른 조건·중복 값은 유지한다', () => {
  const query = new URLSearchParams(
    'tab=one&tab=two&youtubeResult=failed&youtubeError=SECRET&code=SECRET&state=SECRET&error=SECRET&error_description=SECRET&ACCESS_TOKEN=SECRET&refresh_token=SECRET&id_token=SECRET',
  )
  expect(cleanYouTubeReturn(query).toString()).toBe('tab=one&tab=two')
})
