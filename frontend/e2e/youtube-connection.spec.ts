import type { Page } from '@playwright/test'
import { test, expect, makeSong, emptyPage } from './fixtures'

const path = '/library/youtube'
const user = {
  id: makeSong(800).id,
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
type Status =
  'DISCONNECTED' | 'CONNECTED' | 'RECONNECT_REQUIRED' | 'REVOKING' | 'DISABLED'

async function setup(page: Page, status: Status = 'DISCONNECTED') {
  const state = {
    status,
    loggedIn: true,
    reads: 0,
    posts: [] as unknown[],
    deletes: 0,
    readFailure: 0,
    writeFailure: null as null | { status: number; code: string },
    loseDeleteResponse: false,
    deleteResult: 'DISCONNECTED' as Status,
    readGate: Promise.resolve(),
    writeGate: Promise.resolve(),
  }
  await page.route('**/api/v1/me', (r) =>
    r.fulfill(
      state.loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'youtube-test' } }),
  )
  await page.route('**/api/v1/auth/logout', async (r) => {
    state.loggedIn = false
    await r.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/me/youtube/**', async (r) => {
    const request = r.request()
    if (request.method() === 'GET') {
      state.reads++
      await state.readGate
      if (state.readFailure === 401) state.loggedIn = false
      return r.fulfill(
        state.readFailure
          ? { status: state.readFailure, json: { code: 'UNAVAILABLE' } }
          : { json: { status: state.status } },
      )
    }
    expect(request.headers()['x-csrf-token']).toBe('youtube-test')
    if (request.method() === 'POST') state.posts.push(request.postDataJSON())
    else state.deletes++
    await state.writeGate
    if (state.writeFailure) {
      if (state.writeFailure.status === 401) state.loggedIn = false
      return r.fulfill({
        status: state.writeFailure.status,
        json: state.writeFailure,
      })
    }
    if (request.method() === 'POST')
      return r.fulfill({
        json: {
          authorizationUrl:
            'https://accounts.google.com/o/oauth2/v2/auth?state=test-only',
          expiresAt: '2100-01-01T00:00:00Z',
        },
      })
    state.status = state.deleteResult
    return state.loseDeleteResponse
      ? r.abort('failed')
      : r.fulfill({ json: { status: state.status } })
  })
  await page.route('https://accounts.google.com/o/oauth2/**', (r) =>
    r.fulfill({
      contentType: 'text/html; charset=utf-8',
      body: '<h1>테스트 인증</h1>',
    }),
  )
  return state
}
const panel = (page: Page) =>
  page.getByRole('region', { name: 'YouTube 연결 상태', exact: true })
async function open(page: Page) {
  await page.goto(path)
  await expect(panel(page)).toBeVisible()
}
async function confirm(page: Page) {
  await page.getByRole('button', { name: '연결 해제', exact: true }).click()
  return page.getByRole('dialog')
}

for (const [status, title, action, disabled] of [
  ['DISCONNECTED', 'YouTube 계정을 연결하세요', 'YouTube 연결', false],
  ['CONNECTED', 'YouTube가 연결되어 있어요', '연결 해제', false],
  ['RECONNECT_REQUIRED', 'YouTube에 다시 연결해 주세요', '다시 연결', false],
  ['REVOKING', 'YouTube 연결을 해제하고 있어요', 'YouTube 연결', true],
  ['DISABLED', '지금은 YouTube 연결을 사용할 수 없어요', 'YouTube 연결', true],
] as const) {
  test(`${status} 상태를 표시하고 허용된 동작만 제공한다`, async ({ page }) => {
    const state = await setup(page, status)
    await open(page)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    const button = panel(page).getByRole('button', {
      name: action,
      exact: true,
    })
    if (disabled) await expect(button).toBeDisabled()
    else await expect(button).toBeEnabled()
    expect(state.posts).toHaveLength(0)
    expect(state.deletes).toBe(0)
    await expect(
      page.getByRole('link', { name: '플레이리스트로 돌아가기' }),
    ).toHaveAttribute('href', '/library/playlists')
  })
}

test('로딩과 조회 오류를 미연결과 구분하고 수동 재조회한다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.readGate = new Promise<void>((resolve) => (release = resolve))
  await page.goto(path)
  await expect(
    page.getByRole('status', { name: 'YouTube 연결 상태 확인 중' }),
  ).toBeVisible()
  state.readFailure = 403
  release()
  await expect(page.getByText('연결 상태를 불러오지 못했어요')).toBeVisible()
  await expect(page.getByText('미연결', { exact: true })).toHaveCount(0)
  state.readFailure = 0
  state.status = 'CONNECTED'
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page.getByText('YouTube가 연결되어 있어요')).toBeVisible()
  expect(state.posts).toHaveLength(0)
})

for (const empty of [false, true]) {
  test(`플레이리스트 ${empty ? '빈' : '정상'} 목록에서 연결 관리에 진입한다`, async ({
    page,
  }) => {
    await setup(page)
    await page.route('**/api/v1/me/playlists**', (r) =>
      r.fulfill({
        json: {
          ...emptyPage,
          totalCount: empty ? 0 : 1,
          items: empty
            ? []
            : [
                {
                  id: makeSong(900).id,
                  name: '테스트 목록',
                  totalCount: 0,
                  availableCount: 0,
                  version: 0,
                  containsSong: null,
                  createdAt: '2026-10-20T01:00:00Z',
                  updatedAt: '2026-10-20T01:00:00Z',
                },
              ],
        },
      }),
    )
    await page.goto('/library/playlists')
    await page
      .getByRole('link', { name: 'YouTube 연결 관리', exact: true })
      .click()
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    await expect(panel(page)).toBeVisible()
  })
}

test('CSRF로 인증을 한 번 시작하고 응답 URL로 브라우저를 이동한다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.writeGate = new Promise<void>((resolve) => (release = resolve))
  await open(page)
  await page.getByRole('button', { name: 'YouTube 연결', exact: true }).click()
  await expect(page.getByText('Google 인증을 여는 중이에요')).toBeVisible()
  await expect(panel(page).getByRole('button')).toBeDisabled()
  await expect
    .poll(() => state.posts)
    .toEqual([{ returnTo: path, callbackClient: 'default' }])
  release()
  await expect(page.getByRole('heading', { name: '테스트 인증' })).toBeVisible()
  expect(new URL(page.url()).origin).toBe('https://accounts.google.com')
  expect(state.posts).toHaveLength(1)
})

for (const destination of ['playlist', 'header', 'back', 'revisit'] as const) {
  test(`인증 응답 전에 ${destination} 이동한 화면을 뒤늦게 Google로 전환하지 않는다`, async ({
    page,
  }) => {
    const state = await setup(page)
    let release!: () => void
    state.writeGate = new Promise<void>((resolve) => (release = resolve))
    await page.goto('/library/playlists')
    await page
      .getByRole('link', { name: 'YouTube 연결 관리', exact: true })
      .click()
    await expect(panel(page)).toBeVisible()
    await page
      .getByRole('button', { name: 'YouTube 연결', exact: true })
      .click()
    await expect(page.getByText('Google 인증을 여는 중이에요')).toBeVisible()
    if (destination === 'header') {
      if (test.info().project.name === 'mobile')
        await page.getByRole('button', { name: '메뉴 열기' }).click()
      await page
        .getByRole('navigation', {
          name:
            test.info().project.name === 'mobile'
              ? '모바일 주 메뉴'
              : '주 메뉴',
          exact: true,
        })
        .getByRole('link', { name: '검색', exact: true })
        .click()
    } else if (destination === 'back') await page.goBack()
    else
      await page.getByRole('link', { name: '플레이리스트로 돌아가기' }).click()
    if (destination === 'revisit')
      await page
        .getByRole('link', { name: 'YouTube 연결 관리', exact: true })
        .click()
    const expected =
      destination === 'header'
        ? '/search'
        : destination === 'revisit'
          ? path
          : '/library/playlists'
    await expect(page).toHaveURL(new RegExp(`${expected}$`))
    const response = page.waitForResponse(
      (r) =>
        r.url().endsWith('/me/youtube/authorizations') &&
        r.request().method() === 'POST',
    )
    release()
    await (await response).finished()
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    )
    await expect(page).toHaveURL(new RegExp(`${expected}$`))
    if (destination === 'revisit') {
      await expect(
        page.getByRole('button', { name: 'YouTube 연결', exact: true }),
      ).toBeEnabled()
      expect(state.posts).toHaveLength(1)
      await page
        .getByRole('button', { name: 'YouTube 연결', exact: true })
        .click()
      await expect(
        page.getByRole('heading', { name: '테스트 인증' }),
      ).toBeVisible()
      expect(state.posts).toHaveLength(2)
    }
  })
}

test('성공 안내는 실제 상태를 확인한 뒤 표시하고 복귀 파라미터를 정리한다', async ({
  page,
}) => {
  await setup(page, 'CONNECTED')
  await page.goto(
    `${path}?keep=1&keep=2&youtubeResult=success&code=secret&state=secret&ACCESS_TOKEN=secret#section`,
  )
  await expect(
    page.getByText('YouTube 계정을 연결했습니다.', { exact: true }),
  ).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${path}\\?keep=1&keep=2#section$`))
  await page.reload()
  await expect(page.getByText('YouTube가 연결되어 있어요')).toBeVisible()
  await expect(
    page.getByText('YouTube 계정을 연결했습니다.', { exact: true }),
  ).toHaveCount(0)
})

test('성공 파라미터만으로 연결 완료를 표시하지 않는다', async ({ page }) => {
  await setup(page)
  await page.goto(`${path}?youtubeResult=success`)
  await expect(
    page.getByText('YouTube 연결 상태를 확인해 주세요.'),
  ).toBeVisible()
  await expect(
    page.getByText('YouTube 계정을 연결했습니다.', { exact: true }),
  ).toHaveCount(0)
  await expect(page.getByText('YouTube 계정을 연결하세요')).toBeVisible()
})

for (const result of ['cancelled', 'failed']) {
  test(`인증 ${result} 안내가 기존 연결 상태를 덮어쓰지 않는다`, async ({
    page,
  }) => {
    const state = await setup(page, 'CONNECTED')
    await page.goto(
      `${path}?youtubeResult=${result}&youtubeError=INTERNAL_SECRET`,
    )
    await expect(page.getByText('YouTube가 연결되어 있어요')).toBeVisible()
    await expect(
      page.getByText(
        result === 'cancelled'
          ? '연결을 취소했습니다. 필요할 때 다시 연결해 주세요.'
          : 'YouTube 연결에 실패했습니다. 다시 시도해 주세요.',
      ),
    ).toBeVisible()
    await expect(page.getByText('INTERNAL_SECRET')).toHaveCount(0)
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    expect(state.posts).toHaveLength(0)
  })
}

test('인증 시작 실패는 자동 재전송하지 않고 최신 상태를 다시 확인한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.writeFailure = { status: 409, code: 'YOUTUBE_REVOCATION_PENDING' }
  await open(page)
  state.status = 'REVOKING'
  await page.getByRole('button', { name: 'YouTube 연결', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '연결 해제가 처리 중입니다. 완료된 뒤 다시 연결해 주세요.',
  )
  await expect(page.getByText('YouTube 연결을 해제하고 있어요')).toBeVisible()
  expect(state.posts).toHaveLength(1)
})

test('해제 확인창은 취소·처리 중 잠금·해제 완료를 처리한다', async ({
  page,
}) => {
  const state = await setup(page, 'CONNECTED')
  await open(page)
  let dialog = await confirm(page)
  await expect(
    dialog.getByText(
      '사이트 플레이리스트와 이미 생성된 YouTube 목록은 유지됩니다.',
    ),
  ).toBeVisible()
  await dialog.getByRole('button', { name: '취소', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.deletes).toBe(0)
  let release!: () => void
  state.writeGate = new Promise<void>((resolve) => (release = resolve))
  dialog = await confirm(page)
  await dialog.getByRole('button', { name: '해제', exact: true }).click()
  await expect(
    dialog.getByRole('button', { name: '취소', exact: true }),
  ).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '해제 중…' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  expect(state.deletes).toBe(1)
  release()
  await expect(dialog).toHaveCount(0)
  await expect(
    page.getByText(
      'YouTube 연결을 해제했습니다. 내보내기 작업 기록이 삭제되었습니다.',
    ),
  ).toBeVisible()
  await expect(page.getByText('YouTube 계정을 연결하세요')).toBeVisible()
})

test('해제 오류는 확인창을 유지하고 사용자가 재시도한다', async ({ page }) => {
  const state = await setup(page, 'CONNECTED')
  state.writeFailure = { status: 403, code: 'ACCESS_DENIED' }
  await open(page)
  const dialog = await confirm(page)
  await dialog.getByRole('button', { name: '해제', exact: true }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  expect(state.deletes).toBe(1)
  state.writeFailure = null
  await dialog.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.deletes).toBe(2)
})

test('해제 응답 유실 후 상태 조회도 실패하면 재시도마다 확인하고 중복 해제하지 않는다', async ({
  page,
}) => {
  const state = await setup(page, 'CONNECTED')
  state.loseDeleteResponse = true
  await open(page)
  const dialog = await confirm(page)
  await dialog.getByRole('button', { name: '해제', exact: true }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  state.readFailure = 403
  const reads = state.reads
  await dialog.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  expect(state.reads).toBeGreaterThan(reads)
  expect(state.deletes).toBe(1)
  state.readFailure = 0
  await dialog.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.deletes).toBe(1)
  await expect(
    page.getByText(
      'YouTube 연결을 해제했습니다. 내보내기 작업 기록이 삭제되었습니다.',
    ),
  ).toBeVisible()
})

test('해제 처리 중에는 재조회만 하고 실제 해제 후 완료를 안내한다', async ({
  page,
}) => {
  const state = await setup(page, 'CONNECTED')
  state.deleteResult = 'REVOKING'
  await open(page)
  const dialog = await confirm(page)
  await dialog.getByRole('button', { name: '해제', exact: true }).click()
  await expect(page.getByText('YouTube 연결을 해제하고 있어요')).toBeVisible()
  await expect(
    page.getByText('YouTube 연결을 해제했습니다.', { exact: false }),
  ).toHaveCount(0)
  state.status = 'DISCONNECTED'
  await expect(
    page.getByText(
      'YouTube 연결을 해제했습니다. 내보내기 작업 기록이 삭제되었습니다.',
    ),
  ).toBeVisible({ timeout: 10000 })
  expect(state.deletes).toBe(1)
})

for (const operation of ['read', 'write']) {
  test(`${operation} 401은 로그인으로 이동하고 YouTube 인증을 자동 시작하지 않는다`, async ({
    page,
  }) => {
    const state = await setup(page)
    if (operation === 'read') {
      state.readFailure = 401
    } else state.writeFailure = { status: 401, code: 'SESSION_EXPIRED' }
    if (operation === 'read') await page.goto(path)
    else {
      await open(page)
      await page
        .getByRole('button', { name: 'YouTube 연결', exact: true })
        .click()
    }
    await expect(page).toHaveURL(
      new RegExp('/login\\?returnTo=%2Flibrary%2Fyoutube$'),
    )
    const writes = state.posts.length
    state.loggedIn = true
    state.readFailure = 0
    state.writeFailure = null
    await open(page)
    expect(state.posts).toHaveLength(writes)
    await expect(
      page.getByRole('button', { name: 'YouTube 연결', exact: true }),
    ).toBeEnabled()
  })
}

test('로그아웃 뒤 도착한 인증 시작 응답은 Google 이동에 사용하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.writeGate = new Promise<void>((resolve) => (release = resolve))
  await open(page)
  await page.getByRole('button', { name: 'YouTube 연결', exact: true }).click()
  await expect(page.getByText('Google 인증을 여는 중이에요')).toBeVisible()
  if (test.info().project.name === 'mobile')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page).toHaveURL(new RegExp('/login'))
  release()
  await expect(page.getByRole('heading', { name: '테스트 인증' })).toHaveCount(
    0,
  )
  expect(new URL(page.url()).hostname).not.toBe('accounts.google.com')
})

test('라이트·다크 정상·로딩·오류·확인창을 화면 크기에 맞게 표시한다', async ({
  page,
}, info) => {
  await page.setViewportSize(
    info.project.name === 'mobile'
      ? { width: 390, height: 844 }
      : { width: 1440, height: 1024 },
  )
  const state = await setup(page, 'CONNECTED')
  await open(page)
  for (const theme of ['light', 'dark']) {
    const switchTheme = page.getByRole('button', {
      name: theme === 'dark' ? '다크 모드로 변경' : '라이트 모드로 변경',
    })
    if (await switchTheme.count()) await switchTheme.click()
    if (process.env.YOUTUBE_VISUAL_DIR)
      await page.screenshot({
        path: `${process.env.YOUTUBE_VISUAL_DIR}/youtube-${info.project.name}-${theme}.png`,
        fullPage: true,
      })
    const dialog = await confirm(page)
    if (process.env.YOUTUBE_VISUAL_DIR)
      await page.screenshot({
        path: `${process.env.YOUTUBE_VISUAL_DIR}/youtube-${info.project.name}-${theme}-confirm.png`,
        fullPage: true,
      })
    await expect(dialog).toBeVisible()
    await expect(page.locator('html')).toHaveJSProperty(
      'scrollWidth',
      await page.locator('html').evaluate((el) => el.clientWidth),
    )
    await page.keyboard.press('Escape')
    for (const status of [
      'DISCONNECTED',
      'RECONNECT_REQUIRED',
      'REVOKING',
      'DISABLED',
    ] as const) {
      state.status = status
      await page.reload()
      await expect(panel(page)).toBeVisible()
      if (process.env.YOUTUBE_VISUAL_DIR)
        await page.screenshot({
          path: `${process.env.YOUTUBE_VISUAL_DIR}/youtube-${info.project.name}-${theme}-${status.toLowerCase()}.png`,
          fullPage: true,
        })
    }
    state.status = 'CONNECTED'
    let release!: () => void
    state.readGate = new Promise<void>((resolve) => (release = resolve))
    await page.reload()
    await expect(
      page.getByRole('status', { name: 'YouTube 연결 상태 확인 중' }),
    ).toBeVisible()
    if (process.env.YOUTUBE_VISUAL_DIR)
      await page.screenshot({
        path: `${process.env.YOUTUBE_VISUAL_DIR}/youtube-${info.project.name}-${theme}-loading.png`,
        fullPage: true,
      })
    state.readFailure = 403
    release()
    await expect(page.getByText('연결 상태를 불러오지 못했어요')).toBeVisible()
    if (process.env.YOUTUBE_VISUAL_DIR)
      await page.screenshot({
        path: `${process.env.YOUTUBE_VISUAL_DIR}/youtube-${info.project.name}-${theme}-error.png`,
        fullPage: true,
      })
    state.readFailure = 0
    await page.getByRole('button', { name: '다시 시도', exact: true }).click()
    await expect(page.getByText('YouTube가 연결되어 있어요')).toBeVisible()
  }
})
