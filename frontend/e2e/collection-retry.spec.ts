import { test, expect, makeSong } from './fixtures'
import { mockAdmin, run as baseRun } from './admin-fixtures'
import type { Page } from '@playwright/test'
const id = makeSong(992).id,
  path = `/admin/collection-runs/VIDEO/${id}`
async function setup(page: Page) {
  await mockAdmin(page)
  const now = new Date(),
    state = {
      run: {
        ...baseRun,
        logicalSlot: new Date(now.getTime() - 3600000).toISOString(),
        startedAt: new Date(now.getTime() - 3600000).toISOString(),
        status: 'FAILED',
      },
      overview: {
        checkedAt: now.toISOString(),
        retryEnabled: true,
        activeRetry: null as unknown,
      },
      writes: [] as Record<string, unknown>[],
      retry: null as Record<string, unknown> | null,
      mode: 'success',
      getError: 0,
      postGate: Promise.resolve(),
    }
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf-test' } }),
  )
  await page.route('**/api/v1/admin/collection-status', (r) =>
    r.fulfill({ json: state.overview }),
  )
  await page.route('**/api/v1/admin/collection-runs/VIDEO/*', (r) =>
    r.fulfill({
      json: { ...state.run, id: r.request().url().split('/').at(-1) },
    }),
  )
  await page.route('**/api/v1/admin/collection-retries/*', (r) =>
    state.getError
      ? r.fulfill({ status: state.getError, json: { code: 'UNAVAILABLE' } })
      : state.retry
        ? r.fulfill({ json: state.retry })
        : r.fulfill({
            status: 404,
            json: { code: 'COLLECTION_RETRY_NOT_FOUND' },
          }),
  )
  await page.route(
    '**/api/v1/admin/collection-runs/VIDEO/*/retries',
    async (r) => {
      expect(r.request().headers()['x-csrf-token']).toBe('csrf-test')
      const b = r.request().postDataJSON()
      state.writes.push(b)
      await state.postGate
      if (state.mode.startsWith('COLLECTION_'))
        return r.fulfill({
          status: state.mode === 'COLLECTION_RETRY_DISABLED' ? 503 : 409,
          json: { code: state.mode },
        })
      state.retry = {
        id: b.requestId,
        kind: 'VIDEO',
        runId: id,
        expectedAttempt: b.attempt,
        status: 'QUEUED',
        createdAt: now.toISOString(),
        startedAt: null,
        finishedAt: null,
        executionRunId: null,
        errorCode: null,
      }
      if (state.mode === 'lost') return r.abort('failed')
      return r.fulfill({ status: 202, json: state.retry })
    },
  )
  return state
}
async function submit(page: Page) {
  await page
    .getByLabel('재시도 사유 *', { exact: true })
    .fill('네트워크 복구 확인')
  await page.getByRole('button', { name: '재시도 확인', exact: true }).click()
  await page.getByRole('button', { name: '확인 후 저장', exact: true }).click()
}
test('목록에서 상세를 열고 확인 후에만 접수하며 대기와 완료를 구분한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(
    path +
      '?returnTo=' +
      encodeURIComponent(
        '/admin/failures?from=2026-10-30T00:00:00Z&to=2026-10-31T00:00:00Z&page=1',
      ),
  )
  await page.getByRole('button', { name: '재시도 확인', exact: true }).click()
  expect(s.writes).toHaveLength(0)
  await submit(page)
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', {
      name: '수집 재시도가 완료되었습니다',
      exact: true,
    }),
  ).toHaveCount(0)
  s.retry = {
    ...s.retry,
    status: 'SUCCEEDED',
    executionRunId: makeSong(999).id,
    finishedAt: new Date().toISOString(),
  }
  await page.getByRole('button', { name: '상태 새로고침', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '수집 재시도가 완료되었습니다',
      exact: true,
    }),
  ).toBeVisible()
  await page.getByRole('link', { name: '실행 작업 확인', exact: true }).click()
  await expect(page).toHaveURL(
    new RegExp(`/admin/collection-runs/VIDEO/${makeSong(999).id}`),
  )
  expect(s.writes).toHaveLength(1)
})
test('응답 유실·새로고침 후 보관한 ID로 결과부터 복구한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.mode = 'lost'
  await page.goto(path)
  await submit(page)
  await expect(
    page.getByRole('heading', {
      name: '접수 결과를 확인할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  await page.reload()
  await page
    .getByRole('button', { name: '최신 결과 확인', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes).toHaveLength(1)
})
test('조회 장애는 미접수로 보지 않고 명확한 404 후에만 동일 본문을 재전송한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.mode = 'lost'
  await page.goto(path)
  await submit(page)
  await expect(
    page.getByRole('heading', {
      name: '접수 결과를 확인할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  s.getError = 503
  await page
    .getByRole('button', { name: '최신 결과 확인', exact: true })
    .click()
  await expect(
    page.getByRole('button', { name: '같은 요청 재전송', exact: true }),
  ).toBeDisabled()
  s.getError = 0
  s.retry = null
  await page
    .getByRole('button', { name: '최신 결과 확인', exact: true })
    .click()
  await expect(
    page.getByRole('button', { name: '같은 요청 재전송', exact: true }),
  ).toBeEnabled()
  s.mode = 'success'
  await page
    .getByRole('button', { name: '같은 요청 재전송', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes[1]).toEqual(s.writes[0])
})
for (const mode of ['disabled', 'expired', 'success-status', 'active'])
  test(`${mode} 상태에서는 새 접수를 막는다`, async ({ page }) => {
    const s = await setup(page)
    if (mode === 'disabled') s.overview.retryEnabled = false
    if (mode === 'expired') s.run.logicalSlot = '2020-01-01T00:00:00Z'
    if (mode === 'success-status') s.run.status = 'SUCCEEDED'
    if (mode === 'active')
      s.overview.activeRetry = {
        id: makeSong(995).id,
        kind: 'VIDEO',
        runId: id,
        expectedAttempt: 2,
        status: 'RUNNING',
        createdAt: new Date().toISOString(),
        startedAt: null,
        finishedAt: null,
        executionRunId: null,
        errorCode: null,
      }
    await page.goto(path)
    await expect(
      page.getByRole('button', { name: '재시도 확인', exact: true }),
    ).toBeDisabled()
    expect(s.writes).toHaveLength(0)
    if (mode === 'active') {
      s.retry = s.overview.activeRetry as Record<string, unknown>
      await page
        .getByRole('link', { name: '기존 요청 확인', exact: true })
        .click()
      await expect(
        page.getByRole('heading', { name: '수집 재시도 실행 중', exact: true }),
      ).toBeVisible()
    }
  })
for (const code of [
  'COLLECTION_RETRY_EXPIRED',
  'COLLECTION_RUN_CHANGED',
  'COLLECTION_RETRY_DISABLED',
])
  test(`${code} 확정 오류에서 입력을 보존한다`, async ({ page }) => {
    const s = await setup(page)
    s.mode = code
    await page.goto(path)
    await submit(page)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByLabel('재시도 사유 *', { exact: true })).toHaveValue(
      '네트워크 복구 확인',
    )
    expect(s.writes).toHaveLength(1)
    await expect(
      page.getByRole('heading', {
        name: '접수 결과를 확인할 수 없습니다',
        exact: true,
      }),
    ).toHaveCount(0)
  })
test('탭 저장소가 차단되면 전송하지 않는다', async ({ page }) => {
  const s = await setup(page)
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => {
      throw new Error('blocked')
    }
  })
  await page.goto(path)
  await submit(page)
  await expect(
    page.getByText('요청을 탭에 보관할 수 없어 전송하지 않았습니다.', {
      exact: true,
    }),
  ).toBeVisible()
  expect(s.writes).toHaveLength(0)
})
test('PC·모바일 양쪽 테마의 상세·확인창이 화면을 넘지 않는다', async ({
  page,
}, info) => {
  await setup(page)
  await page.goto(path)
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await expect(
      page.getByLabel('재시도 사유 *', { exact: true }),
    ).toBeVisible()
    await page.screenshot({
      path: info.outputPath(`retry-${theme}.png`),
      fullPage: true,
    })
    await page.getByLabel('재시도 사유 *', { exact: true }).fill('검증용 사유')
    await page.getByRole('button', { name: '재시도 확인', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: info.outputPath(`retry-confirm-${theme}.png`),
      fullPage: true,
    })
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '취소', exact: true })
      .click()
  }
})

test('미저장 이동은 확인하고 접수 중에는 중복 실행·이동을 막는다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(path)
  await page.getByLabel('재시도 사유 *', { exact: true }).fill('보존할 사유')
  await page.getByRole('link', { name: '노래 목록', exact: true }).click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await expect(page.getByLabel('재시도 사유 *', { exact: true })).toHaveValue(
    '보존할 사유',
  )
  let release!: () => void
  s.postGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await submit(page)
  await expect(
    page.getByRole('button', { name: '재시도 확인', exact: true }),
  ).toBeDisabled()
  await page.getByRole('link', { name: '노래 목록', exact: true }).click()
  await expect(page).toHaveURL(path)
  release()
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes).toHaveLength(1)
})

test('재시도 실행 실패를 완료로 표시하지 않고 실행 결과는 읽기 전용으로 연다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(path)
  await submit(page)
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  s.retry = {
    ...s.retry,
    status: 'FAILED',
    executionRunId: makeSong(999).id,
    errorCode: 'YOUTUBE_NETWORK_ERROR',
    finishedAt: new Date().toISOString(),
  }
  await page.getByRole('button', { name: '상태 새로고침', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '재시도 작업에 실패했습니다',
      exact: true,
    }),
  ).toBeVisible()
  await page.getByRole('link', { name: '실행 작업 확인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '실행 작업 결과', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '재시도 확인', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('link', { name: '원본 실패 작업', exact: true }),
  ).toHaveAttribute('href', new RegExp(`/admin/collection-runs/VIDEO/${id}`))
})

for (const status of [401, 403])
  test(`접수 ${status} 이후 자동 재전송하지 않는다`, async ({ page }) => {
    const s = await setup(page)
    await page.route('**/api/v1/admin/collection-runs/VIDEO/*/retries', (r) =>
      r.fulfill({
        status,
        json: { code: status === 401 ? 'UNAUTHENTICATED' : 'ACCESS_DENIED' },
      }),
    )
    await page.goto(path)
    await submit(page)
    await expect(
      page.getByRole('heading', {
        name: status === 401 ? '로그인이 필요해요' : '관리자 권한이 없어요',
        exact: true,
      }),
    ).toBeVisible()
    expect(s.writes).toHaveLength(0)
  })

test('관리 메뉴와 실패 목록의 상세 진입이 조회 기간·페이지를 유지한다', async ({
  page,
}) => {
  await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page
    .getByRole('link', { name: '실패 작업 재시도', exact: true })
    .click()
  await page
    .getByRole('link', { name: '작업 상세', exact: true })
    .first()
    .click()
  await expect(
    page.getByRole('heading', { name: '실패 작업 상세', exact: true }),
  ).toBeVisible()
  const url = new URL(page.url()),
    returnTo = url.searchParams.get('returnTo')!
  expect(new URL(returnTo, 'https://local').searchParams.has('from')).toBe(true)
  expect(new URL(returnTo, 'https://local').searchParams.has('to')).toBe(true)
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL(returnTo)
})

test('실행 실패 뒤 원본을 다시 확인하고 명시적으로 새 요청을 만들 수 있다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(path)
  await submit(page)
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  s.retry = {
    ...s.retry,
    status: 'FAILED',
    errorCode: 'YOUTUBE_NETWORK_ERROR',
    finishedAt: new Date().toISOString(),
  }
  await page.getByRole('button', { name: '상태 새로고침', exact: true }).click()
  await page
    .getByRole('button', { name: '원본 작업 확인', exact: true })
    .click()
  await expect(page.getByLabel('재시도 사유 *', { exact: true })).toHaveValue(
    '',
  )
  await submit(page)
  await expect(
    page.getByRole('heading', { name: '재시도가 접수되었습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes).toHaveLength(2)
  expect(s.writes[0]?.requestId).not.toBe(s.writes[1]?.requestId)
})
