import { http, HttpResponse } from 'msw'
import { beforeEach, expect, it } from 'vitest'
import { server } from '@/test/server'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import {
  getWithdrawalConfirmation,
  isConfirmed,
  startReauthentication,
  withdrawAccount,
  prepareWithdrawal,
  getWithdrawalResult,
} from './api'
const receipt = {
  id: '11111111-1111-4111-8111-111111111111',
  queryToken: 'a'.repeat(43),
  expiresAt: '2100-01-01T00:00:00Z',
}
import {
  cleanReauthenticationReturn,
  readReauthenticationResult,
  readReauthenticationError,
} from './oauth-result'

beforeEach(() => {
  apiClient.invalidateCsrf()
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'account-csrf' }),
    ),
  )
})

it('재인증 요청에 탈퇴 복귀 경로·개발 콜백·CSRF를 전달한다', async () => {
  let body: unknown
  let token: string | null = null
  server.use(
    http.post('*/api/v1/me/reauthentications', async ({ request }) => {
      body = await request.json()
      token = request.headers.get('X-CSRF-TOKEN')
      return HttpResponse.json({
        authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        expiresAt: '2100-01-01T00:00:00Z',
      })
    }),
  )
  await startReauthentication()
  expect(body).toEqual({
    returnTo: '/account/withdrawal',
    callbackClient: 'local',
  })
  expect(token).toBe('account-csrf')
})

it.each([
  { reauthenticated: true, expiresAt: null },
  { reauthenticated: false, expiresAt: '2100-01-01T00:00:00Z' },
  { reauthenticated: true, expiresAt: 'not-a-date' },
  {},
])('불완전한 재인증 응답을 허용하지 않는다: %j', async (value) => {
  server.use(
    http.get('*/api/v1/me/withdrawal-confirmation', () =>
      HttpResponse.json(value),
    ),
  )
  await expect(getWithdrawalConfirmation()).rejects.toBeInstanceOf(
    ApiResponseError,
  )
})

it('유효 여부는 서버가 반환한 만료 시각을 따르며 경계 시각부터 만료한다', () => {
  const value = { reauthenticated: true, expiresAt: '2026-10-24T12:00:00Z' }
  expect(isConfirmed(value, Date.parse(value.expiresAt) - 1)).toBe(true)
  expect(isConfirmed(value, Date.parse(value.expiresAt))).toBe(false)
  expect(isConfirmed({ reauthenticated: false, expiresAt: null })).toBe(false)
})

it.each([
  'javascript:alert(1)',
  'http://accounts.google.com/o/oauth2/v2/auth',
  'https://accounts.google.com.evil.test/o/oauth2/v2/auth',
  'https://user@accounts.google.com/o/oauth2/v2/auth',
  'https://accounts.google.com/o/oauth2/v2/auth#token',
  'https://accounts.google.com/other',
])(
  '허용하지 않은 재인증 주소로 이동하지 않는다: %s',
  async (authorizationUrl) => {
    server.use(
      http.post('*/api/v1/me/reauthentications', () =>
        HttpResponse.json({
          authorizationUrl,
          expiresAt: '2100-01-01T00:00:00Z',
        }),
      ),
    )
    await expect(startReauthentication()).rejects.toBeInstanceOf(
      ApiResponseError,
    )
  },
)

it('만료된 재인증 시작 응답을 사용하지 않는다', async () => {
  server.use(
    http.post('*/api/v1/me/reauthentications', () =>
      HttpResponse.json({
        authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        expiresAt: '2000-01-01T00:00:00Z',
      }),
    ),
  )
  await expect(startReauthentication()).rejects.toBeInstanceOf(ApiResponseError)
})

it('탈퇴는 CSRF를 포함한 DELETE로 요청하고 204만 완료로 처리한다', async () => {
  let writes = 0
  server.use(
    http.delete('*/api/v1/me', ({ request }) => {
      expect(request.headers.get('X-CSRF-TOKEN')).toBe('account-csrf')
      expect(request.headers.get('Withdrawal-Request-Id')).toBe(receipt.id)
      writes++
      return new HttpResponse(null, { status: 204 })
    }),
  )
  await withdrawAccount(receipt.id)
  expect(writes).toBe(1)
  server.use(http.delete('*/api/v1/me', () => HttpResponse.json({})))
  await expect(withdrawAccount(receipt.id)).rejects.toBeInstanceOf(
    ApiResponseError,
  )
})

it('탈퇴 전에 본문 없이 조회 권한을 발급받으며 GET은 토큰 헤더만 사용한다', async () => {
  server.use(
    http.post('*/api/v1/me/withdrawal-requests', async ({ request }) => {
      expect(await request.text()).toBe('')
      expect(request.headers.get('X-CSRF-TOKEN')).toBe('account-csrf')
      return HttpResponse.json(receipt, { status: 201 })
    }),
    http.get('*/api/v1/withdrawal-requests/:id', ({ request }) => {
      expect(request.headers.get('X-Withdrawal-Token')).toBe(receipt.queryToken)
      expect(request.headers.get('X-CSRF-TOKEN')).toBeNull()
      expect(request.url).not.toContain(receipt.queryToken)
      return HttpResponse.json({
        id: receipt.id,
        expiresAt: receipt.expiresAt,
        completedAt: null,
        code: null,
        pollAfterSeconds: 3,
        status: 'PROCESSING',
      })
    }),
  )
  expect(await prepareWithdrawal()).toEqual(receipt)
  expect((await getWithdrawalResult(receipt)).status).toBe('PROCESSING')
})

it.each([
  { status: 'COMPLETED', completedAt: null, pollAfterSeconds: null },
  { status: 'PROCESSING', completedAt: null, pollAfterSeconds: 0 },
  { status: 'UNKNOWN', completedAt: null, pollAfterSeconds: 3 },
  {
    status: 'COMPLETED',
    completedAt: '2026-10-24T00:00:00Z',
    pollAfterSeconds: null,
    id: '22222222-2222-4222-8222-222222222222',
  },
])(
  '불완전하거나 다른 요청의 결과를 완료로 처리하지 않는다: %j',
  async (value) => {
    server.use(
      http.get('*/api/v1/withdrawal-requests/:id', () =>
        HttpResponse.json({ ...receipt, code: null, ...value }),
      ),
    )
    await expect(getWithdrawalResult(receipt)).rejects.toBeInstanceOf(
      ApiResponseError,
    )
  },
)

it.each([
  ['', null],
  ['reauthResult=success', 'success'],
  [
    'reauthResult=cancelled&reauthError=GOOGLE_REAUTHENTICATION_CANCELLED',
    'cancelled',
  ],
  [
    'reauthResult=failed&reauthError=REAUTHENTICATION_ACCOUNT_MISMATCH',
    'failed',
  ],
  ['reauthResult=success&reauthResult=failed', 'failed'],
  ['reauthResult=success&reauthError=anything', 'failed'],
  ['reauthError=unknown', 'failed'],
] as const)('복귀 결과는 안내용으로만 읽는다: %s', (query, result) => {
  expect(readReauthenticationResult(new URLSearchParams(query))).toBe(result)
})

it('콜백의 민감 파라미터·허용하지 않은 오류 원문을 남기지 않는다', () => {
  const params = new URLSearchParams(
    'keep=1&keep=2&reauthResult=failed&reauthError=SECRET&code=SECRET&state=SECRET&error=SECRET&error_description=SECRET&ACCESS_TOKEN=SECRET&refresh_token=SECRET&id_token=SECRET',
  )
  expect(cleanReauthenticationReturn(params).toString()).toBe('keep=1&keep=2')
  expect(readReauthenticationError(params)).toBeUndefined()
  expect(
    readReauthenticationError(
      new URLSearchParams('reauthError=SESSION_EXPIRED'),
    ),
  ).toBe('SESSION_EXPIRED')
})
