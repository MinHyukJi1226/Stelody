import { test, expect } from './fixtures'
import { mockAdmin, inbox } from './admin-fixtures'

test('권한 확인 중에는 관리자 진입과 데이터 요청을 노출하지 않는다', async ({
  page,
}) => {
  const state = await mockAdmin(page)
  let release!: () => void
  state.sessionGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto('/admin/reviews')
  await expect(page.getByText('관리자 권한을 확인하고 있어요')).toBeVisible()
  expect(state.calls).toHaveLength(0)
  await expect(
    page.getByRole('link', { name: '관리자', exact: true }),
  ).toHaveCount(0)
  release()
  await expect(page.getByText('신규 후보 1', { exact: true })).toBeVisible()
})

for (const mode of ['anonymous', 'member', 'inactive', 'error'] as const) {
  test(`관리자 진입 차단: ${mode}`, async ({ page }) => {
    const state = await mockAdmin(page)
    if (mode === 'anonymous') state.loggedIn = false
    if (mode === 'member') state.role = 'USER'
    if (mode === 'inactive') state.status = 'WITHDRAWN'
    if (mode === 'error') state.sessionError = 503
    await page.goto('/admin/reviews?page=1')
    await expect(
      page.getByText(
        mode === 'anonymous'
          ? '로그인이 필요해요'
          : mode === 'error'
            ? '권한을 확인하지 못했어요'
            : '관리자 권한이 없어요',
      ),
    ).toBeVisible()
    expect(state.calls).toHaveLength(0)
    if (mode === 'anonymous')
      await expect(
        page.getByRole('link', { name: '로그인', exact: true }).last(),
      ).toHaveAttribute('href', '/login?returnTo=%2Fadmin%2Freviews%3Fpage%3D1')
    if (mode === 'error') {
      state.sessionError = 0
      await page.getByRole('button', { name: '다시 조회' }).click()
      await expect(page.getByText('신규 후보 2', { exact: true })).toBeVisible()
    }
  })
}

test('ACTIVE ADMIN의 헤더·모바일 메뉴 진입과 작업함 집계를 연결한다', async ({
  page,
  isMobile,
}) => {
  const state = await mockAdmin(page)
  await page.goto('/account')
  await expect(
    page.getByRole('heading', { name: '계정 관리', exact: true }),
  ).toBeVisible()
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page
    .getByRole('link', {
      name: isMobile ? '관리자 작업함' : '관리자',
      exact: true,
    })
    .click()
  await expect(
    page.getByRole('heading', { name: '관리자 작업함', exact: true }),
  ).toBeVisible()
  await expect(page.getByText('4개 작업', { exact: true })).toBeVisible()
  await expect(
    page.getByText('2026.10.24 00:12 KST', { exact: true }),
  ).toBeVisible()
  await page.getByRole('link', { name: '목록 보기' }).nth(3).click()
  await expect(page.getByText('종료 시각 없음', { exact: true })).toBeVisible()
  const call = state.calls.find((u) =>
    u.pathname.endsWith('collection-failures'),
  )!
  expect(call.searchParams.get('from')).toBe(inbox.failureWindowStart)
  expect(call.searchParams.get('to')).toBe(inbox.checkedAt)
  expect(state.methods.every((m) => m === 'GET')).toBe(true)
})

for (const [path, title, text] of [
  ['reviews', '신규 검토', '신규 후보'],
  ['registrations', '정보 보완', '정보 보완 곡'],
  ['special-event-reviews', '특별 목적 후보', '특별 후보'],
  ['failures', '수집 실패 작업', 'QUOTA_EXHAUSTED'],
] as const) {
  test(`${title}: 페이지·필터·실패 기간 유지와 오류 복구`, async ({ page }) => {
    const state = await mockAdmin(page)
    await page.goto(`/admin/${path}`)
    await expect(
      page.getByText(path === 'failures' ? text : `${text} 1`, {
        exact: path !== 'special-event-reviews',
      }),
    ).toBeVisible()
    const initial = new URL(page.url())
    await expect(
      page.getByRole('button', { name: '이전', exact: true }),
    ).toBeDisabled()
    state.errorPage = 1
    await page.getByRole('button', { name: '다음', exact: true }).click()
    await expect(
      page.getByRole('heading', { name: '조회하지 못했어요' }),
    ).toBeVisible()
    expect(new URL(page.url()).searchParams.get('page')).toBe('1')
    state.errorPage = -1
    await page.getByRole('button', { name: '다시 조회' }).click()
    await expect(page.getByText('2페이지', { exact: true })).toBeVisible()
    await expect(
      page.getByRole('button', { name: '다음', exact: true }),
    ).toBeDisabled()
    const call = state.calls
      .filter((u) => u.searchParams.get('page') === '1')
      .at(-1)!
    expect(call.searchParams.get('size')).toBe('20')
    if (path === 'reviews') {
      expect(call.searchParams.get('status')).toBe('PENDING')
      expect(call.searchParams.get('disposition')).toBe('REVIEW')
    }
    if (path === 'registrations') {
      expect(call.searchParams.get('incompleteOnly')).toBe('true')
      await expect(
        page.getByText('원곡 · 별칭 (선택) · TJ 확인', { exact: true }),
      ).toBeVisible()
    }
    if (path === 'special-event-reviews') {
      expect(call.searchParams.get('status')).toBe('PENDING')
      await expect(
        page.getByText('유효한 근거 없음', { exact: true }),
      ).toBeVisible()
      await expect(page.getByText('재확인 필요', { exact: true })).toBeVisible()
    }
    if (path === 'failures') {
      expect(call.searchParams.get('from')).toBe(
        initial.searchParams.get('from'),
      )
      expect(call.searchParams.get('to')).toBe(initial.searchParams.get('to'))
    }
    await page.getByRole('button', { name: '이전', exact: true }).click()
    await expect(page.getByText('1페이지', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: '작업함으로 돌아가기' }).click()
    await expect(page).toHaveURL('/admin')
  })
}

for (const [status, title] of [
  [401, '로그인이 필요해요'],
  [403, '관리자 권한이 없어요'],
  [503, '조회하지 못했어요'],
] as const) {
  test(`관리자 API ${status}를 별도 상태로 처리한다`, async ({ page }) => {
    const state = await mockAdmin(page)
    state.error = status
    await page.goto('/admin/registrations?page=1')
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    await expect(page.getByText('정보 보완 곡 2', { exact: true })).toHaveCount(
      0,
    )
    await expect(page.getByText('0건', { exact: true })).toHaveCount(0)
    if (status === 401)
      await expect(
        page.getByRole('link', { name: '로그인', exact: true }).last(),
      ).toHaveAttribute(
        'href',
        '/login?returnTo=%2Fadmin%2Fregistrations%3Fpage%3D1',
      )
    if (status === 503) {
      state.error = 0
      await page.getByRole('button', { name: '다시 조회' }).click()
      await expect(
        page.getByText('정보 보완 곡 2', { exact: true }),
      ).toBeVisible()
    }
  })
}

test('성공 이력 없음·지연을 정상 0으로 대체하지 않는다', async ({ page }) => {
  const state = await mockAdmin(page)
  state.nullHealth = true
  await page.goto('/admin/collection')
  for (const text of [
    '성공 기록 없음',
    '실행 기록 없음',
    '실패 기록 없음',
    '수집 지연',
    '장시간 실행 중',
  ])
    await expect(page.getByText(text, { exact: true })).toBeVisible()
  await expect(
    page.getByRole('button', { name: '실패 작업 재시도', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByText('일일 집계', { exact: true })).toHaveCount(0)
})

test('목록 로딩·빈 목록과 실제 집계 0을 구분한다', async ({ page }) => {
  const state = await mockAdmin(page)
  let release!: () => void
  state.listGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto('/admin/reviews')
  await expect(page.getByText('불러오는 중…', { exact: true })).toBeVisible()
  await expect(
    page.getByText('목록이 비어 있어요', { exact: true }),
  ).toHaveCount(0)
  state.empty = true
  release()
  await expect(
    page.getByText('목록이 비어 있어요', { exact: true }),
  ).toBeVisible()
  state.zeroCounts = true
  await page.goto('/admin')
  await expect(page.getByText('0건', { exact: true })).toHaveCount(3)
  await expect(page.getByText('0개 작업', { exact: true })).toBeVisible()
})

test('같은 계정의 역할 변경 후 이전 관리자 정보를 다시 표시하지 않는다', async ({
  page,
  isMobile,
}) => {
  const state = await mockAdmin(page)
  await page.goto('/admin/reviews')
  await expect(page.getByText('신규 후보 1', { exact: true })).toBeVisible()
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('link', { name: '계정 관리', exact: true }).click()
  await expect(page).toHaveURL('/account')
  await expect(
    page.getByRole('heading', { name: '계정 관리', exact: true }),
  ).toBeVisible()
  state.role = 'USER'
  await page.goBack()
  await expect(
    page.getByText('관리자 권한이 없어요', { exact: true }),
  ).toBeVisible()
  await expect(page.getByText('신규 후보 1', { exact: true })).toHaveCount(0)
})

test('관리자 정상 화면은 양쪽 테마에서 가로 넘침이 없다', async ({
  page,
  isMobile,
}, info) => {
  await mockAdmin(page)
  await page.setViewportSize(
    isMobile ? { width: 390, height: 844 } : { width: 1440, height: 1024 },
  )
  for (const path of [
    '/admin',
    '/admin/collection',
    '/admin/reviews',
    '/admin/registrations',
    '/admin/failures',
    '/admin/special-event-reviews',
  ]) {
    await page.goto(path)
    await expect(
      page.getByText('관리자 · 조회 전용', { exact: true }),
    ).toBeVisible()
    await expect(page.getByText('불러오는 중…', { exact: true })).toHaveCount(0)
    for (const theme of ['light', 'dark']) {
      if ((await page.locator('html').getAttribute('data-theme')) !== theme) {
        await page
          .getByRole('button', {
            name: theme === 'dark' ? '다크 모드로 변경' : '라이트 모드로 변경',
          })
          .click()
      }
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true)
      await page.screenshot({
        path: info.outputPath(`${path.split('/').at(-1)}-${theme}.png`),
        fullPage: true,
        animations: 'disabled',
      })
    }
  }
  if (!isMobile) {
    for (const width of [768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1024 })
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true)
    }
  }
})

test('실패 기간 없는 직접 진입에서도 요청한 페이지를 유지한다', async ({
  page,
}) => {
  const state = await mockAdmin(page)
  await page.goto('/admin/failures?page=1')
  await expect(page.getByText('2페이지', { exact: true })).toBeVisible()
  const url = new URL(page.url())
  expect(url.searchParams.get('page')).toBe('1')
  expect(url.searchParams.get('from')).toBe(inbox.failureWindowStart)
  expect(url.searchParams.get('to')).toBe(inbox.checkedAt)
  expect(
    state.calls
      .filter((url) => url.pathname.endsWith('collection-failures'))
      .every((url) => url.searchParams.get('page') === '1'),
  ).toBe(true)
})

test('잘못된 성공 응답을 빈 목록·0건으로 표시하지 않는다', async ({ page }) => {
  const state = await mockAdmin(page)
  for (const [path, apiPath] of [
    ['', 'inbox'],
    ['collection', 'collection-status'],
    ['reviews', 'reviews'],
    ['registrations', 'registrations'],
    ['special-event-reviews', 'special-event-reviews'],
    ['failures', 'collection-failures'],
  ]) {
    state.malformedPath = apiPath!
    await page.goto(`/admin/${path}`)
    await expect(
      page.getByRole('heading', { name: '조회하지 못했어요' }),
    ).toBeVisible()
    await expect(
      page.getByText('목록이 비어 있어요', { exact: true }),
    ).toHaveCount(0)
    await expect(page.getByText('0건', { exact: true })).toHaveCount(0)
  }
})
