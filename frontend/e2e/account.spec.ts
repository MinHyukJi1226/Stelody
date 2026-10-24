import type { Page } from '@playwright/test'
import { test, expect, makeSong } from './fixtures'

const path = '/account/withdrawal'
const user = {
  id: makeSong(901).id,
  email: 'member@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const future = () => new Date(Date.now() + 300_000).toISOString()
const requestId = makeSong(910).id
const queryToken = 'a'.repeat(43)
async function setup(page: Page) {
  const state = {
    loggedIn: true,
    email: user.email,
    sessionError: 0,
    confirmationError: 0,
    confirmed: false,
    expiresAt: null as string | null,
    reads: 0,
    sessions: 0,
    deletes: 0,
    authorizations: [] as unknown[],
    csrf: 0,
    failure: null as { status: number; code: string } | null,
    gate: Promise.resolve(),
    preparationGate: Promise.resolve(),
    readGate: Promise.resolve(),
    loseDelete: false,
    preparations: 0,
    prepareError: 0,
    resultReads: 0,
    resultError: 0,
    resultStatus: null as
      null | 'COMPLETED' | 'PROCESSING' | 'UNKNOWN' | 'NOT_COMPLETED',
    receiptExpiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    resultGate: Promise.resolve(),
    connection: 'DISCONNECTED',
    active: null as null | ReturnType<typeof exportJob>,
  }
  await page.route('**/api/v1/me', async (r) => {
    if (r.request().method() === 'DELETE') {
      state.deletes++
      expect(r.request().headers()['x-csrf-token']).toBe('account-csrf')
      expect(r.request().headers()['withdrawal-request-id']).toBe(requestId)
      await state.gate
      if (state.failure) {
        if (state.failure.status === 401) state.loggedIn = false
        return r.fulfill({ status: state.failure.status, json: state.failure })
      }
      state.loggedIn = false
      return state.loseDelete ? r.abort() : r.fulfill({ status: 204 })
    }
    state.sessions++
    await state.readGate
    return r.fulfill(
      state.sessionError
        ? {
            status: state.sessionError,
            json: { code: 'LOGIN_STORAGE_UNAVAILABLE' },
          }
        : state.loggedIn
          ? { json: { ...user, email: state.email } }
          : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    )
  })
  await page.route('**/api/v1/me/withdrawal-requests', async (r) => {
    state.preparations++
    expect(r.request().method()).toBe('POST')
    expect(r.request().postData()).toBeNull()
    expect(r.request().headers()['x-csrf-token']).toBe('account-csrf')
    await state.preparationGate
    return r.fulfill(
      state.prepareError
        ? {
            status: state.prepareError,
            json: { code: 'ACCOUNT_STORAGE_UNAVAILABLE' },
          }
        : {
            status: 201,
            json: {
              id: requestId,
              queryToken,
              expiresAt: state.receiptExpiresAt,
            },
          },
    )
  })
  await page.route('**/api/v1/withdrawal-requests/*', async (r) => {
    state.resultReads++
    expect(r.request().method()).toBe('GET')
    expect(r.request().headers()['x-withdrawal-token']).toBe(queryToken)
    expect(r.request().url()).not.toContain(queryToken)
    await state.resultGate
    if (state.resultError)
      return r.fulfill({
        status: state.resultError,
        json: {
          code:
            state.resultError === 410
              ? 'WITHDRAWAL_RESULT_EXPIRED'
              : state.resultError === 404
                ? 'WITHDRAWAL_RESULT_NOT_FOUND'
                : 'WITHDRAWAL_RESULT_STORAGE_UNAVAILABLE',
        },
      })
    const status =
      state.resultStatus ??
      (state.failure
        ? [401, 403].includes(state.failure.status)
          ? 'UNKNOWN'
          : 'NOT_COMPLETED'
        : 'COMPLETED')
    return r.fulfill({
      json: {
        id: requestId,
        status,
        expiresAt: state.receiptExpiresAt,
        completedAt: status === 'COMPLETED' ? new Date().toISOString() : null,
        code:
          status === 'UNKNOWN'
            ? 'WITHDRAWAL_OUTCOME_UNKNOWN'
            : (state.failure?.code ?? null),
        pollAfterSeconds: status === 'PROCESSING' ? 3 : null,
      },
    })
  })
  await page.route('**/api/v1/auth/csrf', (r) => {
    state.csrf++
    return r.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'account-csrf' },
    })
  })
  await page.route('**/api/v1/auth/logout', (r) => {
    state.loggedIn = false
    return r.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/me/withdrawal-confirmation', (r) => {
    state.reads++
    if (state.confirmationError === 401) state.loggedIn = false
    return r.fulfill(
      state.confirmationError
        ? { status: state.confirmationError, json: { code: 'UNAVAILABLE' } }
        : {
            json: {
              reauthenticated: state.confirmed,
              expiresAt: state.expiresAt,
            },
          },
    )
  })
  await page.route('**/api/v1/me/reauthentications', async (r) => {
    state.authorizations.push(r.request().postDataJSON())
    expect(r.request().headers()['x-csrf-token']).toBe('account-csrf')
    await state.gate
    if (state.failure) {
      if (state.failure.status === 401) state.loggedIn = false
      return r.fulfill({ status: state.failure.status, json: state.failure })
    }
    return r.fulfill({
      json: {
        authorizationUrl:
          'https://accounts.google.com/o/oauth2/v2/auth?state=test-only',
        expiresAt: future(),
      },
    })
  })
  await page.route('**/api/v1/me/youtube-exports/active', (r) =>
    r.fulfill({ json: { job: state.active } }),
  )
  await page.route('**/api/v1/me/youtube/connection', (r) =>
    r.fulfill({ json: { status: state.connection } }),
  )
  await page.route('https://accounts.google.com/o/oauth2/**', (r) =>
    r.fulfill({
      contentType: 'text/html; charset=utf-8',
      body: '<h1>테스트 재인증</h1>',
    }),
  )
  return state
}
function exportJob() {
  return {
    id: makeSong(904).id,
    playlistId: makeSong(905).id,
    version: 1,
    status: 'RUNNING',
    totalCount: 1,
    copiedCount: 0,
    skippedCount: 0,
    remainingCount: 1,
    youtubePlaylistId: null,
    youtubeUrl: null,
    errorCode: null,
    createdAt: '2026-10-24T00:00:00Z',
    updatedAt: '2026-10-24T00:00:00Z',
  }
}
const panel = (page: Page) => page.getByRole('region', { name: '계정 상태' })
async function verified(page: Page) {
  const state = await setup(page)
  state.confirmed = true
  state.expiresAt = future()
  await page.goto(`${path}?reauthResult=success`)
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeEnabled()
  return state
}

test('계정 관리 진입·이메일·탈퇴 안내를 표시하고 메뉴 포커스를 복원한다', async ({
  page,
}, info) => {
  const state = await setup(page)
  await page.goto('/account')
  await expect(panel(page).getByText(user.email, { exact: true })).toBeVisible()
  if (info.project.name === 'mobile') {
    const trigger = page.getByRole('button', { name: '메뉴 열기' })
    await trigger.click()
    const drawer = page.getByRole('dialog', { name: '메뉴' })
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('link', { name: '계정 관리' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await trigger.click()
    await page.mouse.click(10, 200)
    await expect(drawer).toHaveCount(0)
  } else
    await expect(
      page
        .getByRole('navigation', { name: '주 메뉴', exact: true })
        .getByRole('link', { name: '계정 관리' }),
    ).toBeVisible()
  await panel(page).getByRole('link', { name: '회원 탈퇴 안내' }).click()
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 전 안내를 확인해 주세요' }),
  ).toBeVisible()
  expect(state.authorizations).toHaveLength(0)
  expect(state.deletes).toBe(0)
})

test('계정 조회 장애를 비로그인과 구분하고 재시도한다', async ({ page }) => {
  const state = await setup(page)
  state.sessionError = 503
  await page.goto('/account')
  await expect(
    panel(page).getByText('계정 정보를 불러오지 못했어요'),
  ).toBeVisible()
  await expect(panel(page).getByText(user.email)).toHaveCount(0)
  state.sessionError = 0
  await panel(page).getByRole('button', { name: '다시 시도' }).click()
  await expect(panel(page).getByText(user.email)).toBeVisible()
})

test('Google 재인증은 CSRF와 복귀 경로로 한 번 시작하며 브라우저로 이동한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await panel(page)
    .getByRole('button', { name: 'Google 재인증', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '테스트 재인증' }),
  ).toBeVisible()
  expect(state.authorizations).toEqual([
    { returnTo: path, callbackClient: 'default' },
  ])
  expect(state.deletes).toBe(0)
})

test('인증 시작 응답을 기다리는 중 취소하면 늦은 응답으로 이동하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.gate = new Promise<void>((resolve) => (release = resolve))
  await page.goto(path)
  await panel(page)
    .getByRole('button', { name: 'Google 재인증', exact: true })
    .click()
  await expect(
    panel(page).getByRole('button', { name: 'Google 재인증 중…' }),
  ).toBeDisabled()
  await panel(page).getByRole('link', { name: '취소' }).click()
  await expect(page).toHaveURL(/\/account$/)
  release()
  await expect(panel(page).getByText('내 계정', { exact: true })).toBeVisible()
  expect(state.authorizations).toHaveLength(1)
})

for (const [query, title] of [
  [
    'reauthResult=cancelled&reauthError=GOOGLE_REAUTHENTICATION_CANCELLED',
    '본인 확인을 취소했어요',
  ],
  [
    'reauthResult=failed&reauthError=GOOGLE_REAUTHENTICATION_FAILED',
    '본인 확인을 완료하지 못했어요',
  ],
  [
    'reauthResult=failed&reauthError=REAUTHENTICATION_ACCOUNT_MISMATCH',
    '로그인한 계정으로 다시 인증해 주세요',
  ],
] as const)
  test(`복귀 안내 ${query}와 실제 확인을 분리하고 인증 파라미터를 제거한다`, async ({
    page,
  }) => {
    const state = await setup(page)
    await page.goto(
      `${path}?keep=1&keep=2&${query}&code=SECRET&state=SECRET&ACCESS_TOKEN=SECRET#section`,
    )
    await expect(
      panel(page).getByRole('heading', { name: title }),
    ).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`${path}\\?keep=1&keep=2#section$`))
    expect(state.authorizations).toHaveLength(0)
    expect(state.deletes).toBe(0)
  })

test('성공 복귀 파라미터만으로 회원 탈퇴를 활성화하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(`${path}?reauthResult=success`)
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeDisabled()
  expect(state.deletes).toBe(0)
})

test('세션이 없거나 조회가 실패해도 복귀 인증 파라미터를 제거한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.loggedIn = false
  await page.goto(
    `${path}?reauthResult=success&code=SECRET&state=SECRET&keep=1`,
  )
  await expect(
    panel(page).getByRole('link', { name: '다시 로그인' }),
  ).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${path}\\?keep=1$`))
  state.sessionError = 503
  await page.goto(`${path}?reauthResult=failed&code=SECRET`)
  await expect(
    panel(page).getByRole('button', { name: '다시 시도' }),
  ).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${path}$`))
  expect(state.deletes).toBe(0)
})

test('탈퇴 응답 유실은 완료로 추정하거나 탈퇴를 재전송하지 않는다', async ({
  page,
}) => {
  const state = await verified(page)
  state.loseDelete = true
  state.resultStatus = 'UNKNOWN'
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 결과를 확인해야 해요' }),
  ).toBeVisible()
  await expect(
    panel(page).getByRole('button', { name: '탈퇴 결과 확인' }),
  ).toBeEnabled()
  expect(state.deletes).toBe(1)
  expect(state.authorizations).toHaveLength(0)
})

test('페이지 복원은 확인 상태만 재조회하고 재인증·탈퇴를 자동 실행하지 않는다', async ({
  page,
}) => {
  const state = await verified(page)
  const reads = state.reads
  state.confirmed = false
  state.expiresAt = null
  await page.evaluate(() =>
    window.dispatchEvent(
      new PageTransitionEvent('pageshow', { persisted: true }),
    ),
  )
  await expect(
    panel(page).getByRole('button', { name: 'Google 재인증', exact: true }),
  ).toBeEnabled()
  expect(state.reads).toBeGreaterThan(reads)
  expect(state.deletes).toBe(0)
  expect(state.authorizations).toHaveLength(0)
})

test('재인증 실제 상태를 새로고침에도 확인하고 명시적 최종 요청 204 후에만 완료한다', async ({
  page,
}) => {
  const state = await verified(page)
  expect(state.deletes).toBe(0)
  await page.reload()
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeEnabled()
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await expect(
    panel(page).getByRole('heading', { name: '회원 탈퇴가 완료됐어요' }),
  ).toBeVisible()
  await expect(panel(page).getByText(user.email)).toHaveCount(0)
  expect(state.deletes).toBe(1)
  await page.reload()
  await expect(
    panel(page).getByRole('link', { name: '다시 로그인' }),
  ).toBeVisible()
  expect(state.deletes).toBe(1)
})

test('조회 권한 발급·탈퇴 처리 중 중복 제출과 취소를 막는다', async ({
  page,
}) => {
  const state = await verified(page)
  let prepare!: () => void
  state.preparationGate = new Promise<void>((resolve) => (prepare = resolve))
  let release!: () => void
  state.gate = new Promise<void>((resolve) => (release = resolve))
  try {
    await panel(page)
      .getByRole('button', { name: '회원 탈퇴', exact: true })
      .click()
    await expect.poll(() => state.preparations).toBe(1)
    await expect(
      panel(page).getByRole('button', { name: '탈퇴 처리 중…' }),
    ).toBeDisabled()
    await expect(
      panel(page).getByRole('button', { name: '취소' }),
    ).toBeDisabled()
    expect(state.deletes).toBe(0)

    prepare()
    await expect.poll(() => state.deletes).toBe(1)
    await expect(
      panel(page).getByRole('button', { name: '탈퇴 처리 중…' }),
    ).toBeDisabled()
    await expect(
      panel(page).getByRole('button', { name: '취소' }),
    ).toBeDisabled()

    release()
    await expect(
      panel(page).getByRole('heading', { name: '회원 탈퇴가 완료됐어요' }),
    ).toBeVisible()
    expect(state.preparations).toBe(1)
    expect(state.deletes).toBe(1)
  } finally {
    prepare()
    release()
  }
})

test('서버 만료 시각이 지나면 열린 화면에서도 최종 버튼을 잠근다', async ({
  page,
}) => {
  const state = await setup(page)
  const now = Date.now()
  await page.clock.install({ time: now })
  state.confirmed = true
  state.expiresAt = new Date(now + 60_000).toISOString()
  await page.goto(path)
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeEnabled()
  await page.clock.fastForward(60_001)
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeDisabled()
  await expect(
    panel(page).getByText('본인 확인 유효기간 만료', { exact: true }),
  ).toBeVisible()
  expect(state.deletes).toBe(0)
})

for (const [status, code, title] of [
  [409, 'REAUTHENTICATION_REQUIRED', 'Google 본인 확인을 다시 진행해 주세요'],
  [409, 'YOUTUBE_EXPORT_BUSY', '진행 중인 YouTube 작업을 확인해 주세요'],
  [409, 'YOUTUBE_REVOCATION_PENDING', 'YouTube 연결 해제 처리를 기다려 주세요'],
  [503, 'WITHDRAWAL_JOURNAL_UNAVAILABLE', '회원 탈퇴를 완료하지 못했어요'],
  [503, 'LOGIN_STORAGE_UNAVAILABLE', '회원 탈퇴를 완료하지 못했어요'],
  [403, 'ACCESS_DENIED', '탈퇴 결과를 확인해야 해요'],
] as const)
  test(`탈퇴 ${code}를 완료로 표시하거나 자동 재요청하지 않는다`, async ({
    page,
  }) => {
    const state = await verified(page)
    state.failure = { status, code }
    await panel(page)
      .getByRole('button', { name: '회원 탈퇴', exact: true })
      .click()
    await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
    await expect(
      panel(page).getByRole('heading', { name: title }),
    ).toBeVisible()
    expect(state.deletes).toBe(1)
    expect(state.authorizations).toHaveLength(0)
  })

test('YouTube 작업·철회 대기를 다시 확인해도 자동 탈퇴하지 않는다', async ({
  page,
}) => {
  const state = await verified(page)
  state.failure = { status: 409, code: 'YOUTUBE_EXPORT_BUSY' }
  state.active = exportJob()
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
  await panel(page).getByRole('button', { name: '상태 다시 확인' }).click()
  await expect(
    panel(page).getByRole('link', { name: 'YouTube 작업 확인' }),
  ).toHaveAttribute(
    'href',
    `/library/playlists/${state.active.playlistId}/youtube-export?jobId=${state.active.id}`,
  )
  state.active = null
  state.connection = 'REVOKING'
  await panel(page).getByRole('button', { name: '상태 다시 확인' }).click()
  await expect(
    panel(page).getByRole('heading', {
      name: 'YouTube 연결 해제 처리를 기다려 주세요',
    }),
  ).toBeVisible()
  state.connection = 'DISCONNECTED'
  state.failure = null
  await panel(page).getByRole('button', { name: '처리 상태 확인' }).click()
  await expect(
    panel(page).getByRole('button', { name: '회원 탈퇴', exact: true }),
  ).toBeEnabled()
  expect(state.deletes).toBe(1)
})

for (const operation of ['read', 'authorize'])
  test(`${operation} 401은 세션 만료 안내를 표시하고 자동 작업을 하지 않는다`, async ({
    page,
  }) => {
    const state = await setup(page)
    if (operation === 'read') state.confirmationError = 401
    await page.goto(path)
    if (operation !== 'read') {
      state.failure = { status: 401, code: 'SESSION_EXPIRED' }
      await panel(page)
        .getByRole('button', {
          name: 'Google 재인증',
          exact: true,
        })
        .click()
    }
    await expect(
      panel(page).getByRole('link', { name: '다시 로그인' }),
    ).toHaveAttribute('href', `/login?returnTo=${encodeURIComponent(path)}`)
    await expect(panel(page).getByText(user.email)).toHaveCount(0)
  })

for (const returnTo of ['/account', path])
  test(`연결 관리에서 ${returnTo}로 복귀한다`, async ({ page }) => {
    await setup(page)
    await page.goto(`/library/youtube?${new URLSearchParams({ returnTo })}`)
    await expect(
      page.getByRole('link', {
        name:
          returnTo === path ? '탈퇴 화면으로 돌아가기' : '계정 관리로 돌아가기',
      }),
    ).toHaveAttribute('href', returnTo)
  })

test('응답 유실 뒤 새로고침은 무세션에서도 같은 요청의 완료만 조회한다', async ({
  page,
}) => {
  const state = await verified(page)
  state.loseDelete = true
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await expect(
    panel(page).getByRole('button', { name: '탈퇴 결과 확인' }),
  ).toBeEnabled()
  expect(state.resultReads).toBe(0)
  await page.reload()
  await expect(
    panel(page).getByRole('heading', { name: '회원 탈퇴가 완료됐어요' }),
  ).toBeVisible()
  expect(state.deletes).toBe(1)
  expect(state.preparations).toBe(1)
  expect(state.resultReads).toBe(1)
  expect(
    await page.evaluate(() =>
      sessionStorage.getItem('stelody-withdrawal-result'),
    ),
  ).toBeNull()
})

test('처리 중만 3초 간격으로 조회하고 UNKNOWN은 수동 조회만 한다', async ({
  page,
}) => {
  const state = await verified(page)
  state.loseDelete = true
  state.resultStatus = 'PROCESSING'
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
  await expect.poll(() => state.resultReads).toBe(1)
  await page.clock.install()
  state.resultStatus = 'UNKNOWN'
  await page.clock.fastForward(3_001)
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 결과를 확인해야 해요' }),
  ).toBeVisible()
  const reads = state.resultReads
  await page.clock.fastForward(30_000)
  expect(state.resultReads).toBe(reads)
  state.resultStatus = 'COMPLETED'
  await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
  await expect(
    panel(page).getByRole('heading', { name: '회원 탈퇴가 완료됐어요' }),
  ).toBeVisible()
  expect(state.deletes).toBe(1)
  expect(state.preparations).toBe(1)
})

for (const status of [404, 410, 503])
  test(`결과 조회 ${status}는 완료가 아니며 새 DELETE를 보내지 않는다`, async ({
    page,
  }) => {
    const state = await verified(page)
    state.loseDelete = true
    state.resultError = status
    await panel(page)
      .getByRole('button', { name: '회원 탈퇴', exact: true })
      .click()
    await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
    await expect(
      panel(page).getByRole('heading', {
        name:
          status === 503
            ? '서버 오류가 발생했어요'
            : '탈퇴 결과를 확인할 수 없어요',
      }),
    ).toBeVisible()
    expect(state.deletes).toBe(1)
    expect(state.preparations).toBe(1)
    const stored = await page.evaluate(() =>
      sessionStorage.getItem('stelody-withdrawal-result'),
    )
    expect(stored === null).toBe(status === 410)
    if (status !== 410) {
      state.resultError = 0
      await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
      await expect(
        panel(page).getByRole('heading', { name: '회원 탈퇴가 완료됐어요' }),
      ).toBeVisible()
    }
  })

for (const failure of ['prepare', 'storage'])
  test(`${failure} 실패는 탈퇴 요청 전에 멈춘다`, async ({ page }) => {
    const state = await verified(page)
    if (failure === 'prepare') state.prepareError = 503
    else
      await page.evaluate(() => {
        const setItem = Storage.prototype.setItem
        Storage.prototype.setItem = function (key, value) {
          if (key === 'stelody-withdrawal-result')
            throw new DOMException('blocked', 'SecurityError')
          setItem.call(this, key, value)
        }
      })
    await panel(page)
      .getByRole('button', { name: '회원 탈퇴', exact: true })
      .click()
    await expect(
      panel(page).getByRole('heading', { name: '탈퇴를 시작할 수 없어요' }),
    ).toBeVisible()
    expect(state.deletes).toBe(0)
    expect(state.preparations).toBe(1)
  })

test('DELETE 401도 완료로 추정하지 않고 준비된 조회 권한을 유지한다', async ({
  page,
}) => {
  const state = await verified(page)
  state.failure = { status: 401, code: 'SESSION_EXPIRED' }
  await panel(page)
    .getByRole('button', { name: '회원 탈퇴', exact: true })
    .click()
  await panel(page).getByRole('button', { name: '탈퇴 결과 확인' }).click()
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 결과를 확인해야 해요' }),
  ).toBeVisible()
  await expect(panel(page).getByText(user.email)).toHaveCount(0)
  expect(state.deletes).toBe(1)
})

test('저장된 조회 권한이 만료됐으면 완료로 추정하거나 재접수하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.addInitScript(
    ({ requestId, queryToken, user }) =>
      sessionStorage.setItem(
        'stelody-withdrawal-result',
        JSON.stringify({
          id: requestId,
          queryToken,
          user,
          expiresAt: '2000-01-01T00:00:00Z',
        }),
      ),
    { requestId, queryToken, user: user.id },
  )
  await page.goto(path)
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 결과를 확인할 수 없어요' }),
  ).toBeVisible()
  expect(state.deletes).toBe(0)
  expect(state.preparations).toBe(0)
  expect(state.resultReads).toBe(0)
})

test('다른 계정으로 로그인한 화면은 이전 계정의 결과로 로그아웃하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.addInitScript(
    ({ requestId, queryToken }) =>
      sessionStorage.setItem(
        'stelody-withdrawal-result',
        JSON.stringify({
          id: requestId,
          queryToken,
          user: 'another-user',
          expiresAt: '2100-01-01T00:00:00Z',
        }),
      ),
    { requestId, queryToken },
  )
  let release!: () => void
  state.resultGate = new Promise<void>((resolve) => (release = resolve))
  await page.goto(path)
  await expect(panel(page).getByText(user.email)).toBeVisible()
  release()
  await expect(
    panel(page).getByRole('heading', { name: '탈퇴 전 안내를 확인해 주세요' }),
  ).toBeVisible()
  expect(state.deletes).toBe(0)
})

test('모바일 메뉴가 늦게 로드돼도 세션 오류를 자동 재시도하지 않는다', async ({
  page,
}, info) => {
  test.skip(info.project.name !== 'mobile')
  let release!: () => void
  const menu = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/assets/mobile-menu-*.js', async (r) => {
    await menu
    await r.continue()
  })
  const state = await setup(page)
  state.sessionError = 503
  await page.goto('/login')
  await expect(
    page.getByRole('button', { name: '로그인 상태 다시 확인' }),
  ).toBeVisible()
  release()
  await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeVisible()
  expect(state.sessions).toBe(1)
  state.sessionError = 0
  state.loggedIn = false
  await page.getByRole('button', { name: '로그인 상태 다시 확인' }).click()
  await expect(
    page.getByRole('link', { name: 'Google로 로그인' }),
  ).toBeVisible()
  expect(state.sessions).toBe(2)
})

test('계정·탈퇴 화면을 PC·모바일과 라이트·다크에 맞춰 표시한다', async ({
  page,
}, info) => {
  await page.setViewportSize(
    info.project.name === 'mobile'
      ? { width: 390, height: 844 }
      : { width: 1440, height: 1024 },
  )
  const state = await setup(page)
  for (const theme of ['light', 'dark']) {
    await page.addInitScript(
      (value) => localStorage.setItem('stelody-theme', value),
      theme,
    )
    for (const view of [
      'account',
      'guide',
      'verified',
      'expired',
      'wrong',
      'failed',
      'cancelled',
    ]) {
      state.confirmed = ['verified', 'expired'].includes(view)
      state.expiresAt = state.confirmed
        ? view === 'expired'
          ? '2000-01-01T00:00:00Z'
          : future()
        : null
      const query =
        view === 'wrong'
          ? '?reauthResult=failed&reauthError=REAUTHENTICATION_ACCOUNT_MISMATCH'
          : view === 'failed'
            ? '?reauthResult=failed'
            : view === 'cancelled'
              ? '?reauthResult=cancelled'
              : ''
      await page.goto(view === 'account' ? '/account' : path + query)
      await expect(panel(page)).toBeVisible()
      if (view === 'account')
        await expect(panel(page).getByText(user.email)).toBeVisible()
      else {
        const headings: Record<string, string> = {
          guide: '탈퇴 전 안내를 확인해 주세요',
          verified: '마지막으로 탈퇴 여부를 확인해 주세요',
          expired: 'Google 본인 확인을 다시 진행해 주세요',
          wrong: '로그인한 계정으로 다시 인증해 주세요',
          failed: '본인 확인을 완료하지 못했어요',
          cancelled: '본인 확인을 취소했어요',
        }
        await expect(
          panel(page).getByRole('heading', {
            name: headings[view]!,
            exact: true,
          }),
        ).toBeVisible()
      }
      await page.evaluate(() => document.fonts.ready)
      await expect(page.locator('html')).toHaveJSProperty(
        'scrollWidth',
        await page.locator('html').evaluate((el) => el.clientWidth),
      )
      if (process.env.ACCOUNT_VISUAL_DIR)
        await page.screenshot({
          path: `${process.env.ACCOUNT_VISUAL_DIR}/account-${info.project.name}-${theme}-${view}.png`,
          fullPage: true,
        })
    }
  }
})
