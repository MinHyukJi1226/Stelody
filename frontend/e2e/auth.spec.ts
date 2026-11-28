import type { Page } from '@playwright/test'
import { test, expect } from './fixtures'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}

async function accountMenu(page: Page) {
  if (['/search', '/library'].includes(new URL(page.url()).pathname)) {
    const account = page
      .getByRole('banner')
      .getByRole('link', { name: /^(로그인|내 계정)$/ })
    await expect(account).toBeVisible()
    if (
      await page
        .getByRole('banner')
        .getByRole('link', { name: '내 계정', exact: true })
        .count()
    ) {
      await page.getByRole('button', { name: '메뉴 열기' }).click()
    }
    return
  }
  await page.getByRole('button', { name: '메뉴 열기' }).click()
}

test('로그인 화면·원본 Google 아이콘·내부 복귀 경로를 제공한다', async ({
  page,
}) => {
  await page.goto('/search?q=hello&sort=LATEST#results')
  await accountMenu(page)
  await page.getByRole('link', { name: '로그인', exact: true }).click()
  const title = page.getByRole('heading', { name: '로그인', exact: true })
  await expect(title).toBeVisible()
  await expect(
    page.getByText(
      /즐겨찾기와 플레이리스트 저장으로\s*내 취향 곡을 모아보세요/,
    ),
  ).toBeVisible()
  await expect(page.getByRole('search', { name: '노래 검색' })).toBeVisible()
  await expect(page.locator('footer')).toContainText(
    'Stelody는 공식 서비스가 아닌 팬이 제작한 비영리·비공식 서비스입니다.',
  )
  const button = page.getByRole('link', {
    name: 'Google로 로그인',
    exact: true,
  })
  const link = new URL((await button.getAttribute('href'))!, page.url())
  expect(link.pathname).toBe('/api/v1/auth/google')
  expect(link.searchParams.has('loginClient')).toBe(false)
  const callback = new URL(link.searchParams.get('returnTo')!, page.url())
  expect(callback.pathname).toBe('/login')
  expect(callback.search).toBe('')
  await expect(page).toHaveURL('/login')
  expect(
    await page.evaluate(() => sessionStorage.getItem('stelody-login-return')),
  ).toBe('/search?q=hello&sort=LATEST#results')
  expect(await button.boundingBox()).toMatchObject({ height: 52 })
  const mark = button.locator('img')
  expect(await mark.boundingBox()).toMatchObject({ width: 20, height: 20 })
  expect(
    await mark.evaluate(
      (n: HTMLImageElement) => n.complete && n.naturalWidth > 0,
    ),
  ).toBe(true)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('Google 인증을 브라우저 이동으로 시작하고 실제 세션 확인 후 원래 경로로 복귀한다', async ({
  page,
}) => {
  let loggedIn = false
  const meCalls: string[] = []
  await page.route('**/api/v1/me', async (route) => {
    meCalls.push(loggedIn ? 'authenticated' : 'anonymous')
    await route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    )
  })
  await page.route('**/api/v1/auth/google?*', async (route) => {
    expect(route.request().isNavigationRequest()).toBe(true)
    const callback = new URL(
      new URL(route.request().url()).searchParams.get('returnTo')!,
      page.url(),
    )
    expect(callback.pathname).toBe('/login')
    loggedIn = true
    callback.searchParams.set('loginResult', 'success')
    await route.fulfill({
      status: 302,
      headers: { Location: callback.pathname + callback.search },
    })
  })
  await page.goto(
    '/login?returnTo=%2Fsearch%3Fq%3Dtest%26sort%3DVIEWS%23results',
  )
  await page.getByRole('link', { name: 'Google로 로그인' }).click()
  await expect(page).toHaveURL('/search?q=test&sort=VIEWS#results')
  expect(meCalls).toContain('authenticated')
  await accountMenu(page)
  await expect(
    page.getByRole('button', { name: '로그아웃', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '로그인', exact: true }),
  ).toHaveCount(0)
  expect(
    await page.evaluate(() =>
      Object.keys(localStorage).filter((k) =>
        /auth|session|token|user|email/i.test(k),
      ),
    ),
  ).toEqual([])
})

test('임의의 성공 파라미터는 로그인으로 취급하지 않고 취소·실패는 인라인으로 안내한다', async ({
  page,
}) => {
  for (const [result, text] of [
    [
      'success',
      'Google 로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.',
    ],
    [
      'failed',
      'Google 로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.',
    ],
    ['cancelled', '로그인이 취소되었어요. 다시 시도할 수 있어요.'],
  ]) {
    await page.goto(`/login?returnTo=%2Flibrary&loginResult=${result}`)
    await expect(page.getByText(text, { exact: true })).toBeVisible()
    await expect(page).toHaveURL('/login')
    await expect(
      page.getByRole('link', { name: 'Google로 로그인' }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: '로그아웃', exact: true }),
    ).toHaveCount(0)
  }
})

test('취소 시 기존 세션은 유지하고 사용자 선택으로 계속할 수 있다', async ({
  page,
}) => {
  await page.route('**/api/v1/me', (route) => route.fulfill({ json: user }))
  await page.goto('/login?returnTo=%2Flibrary&loginResult=cancelled')
  await expect(
    page.getByText('로그인이 취소되었어요. 다시 시도할 수 있어요.'),
  ).toBeVisible()
  await page.getByRole('link', { name: '현재 계정으로 계속하기' }).click()
  await expect(page).toHaveURL('/library')
  await expect(page.getByRole('heading', { name: '내 보관함' })).toBeVisible()
})

test('세션 로딩·장애·재시도를 구분하며 보호 경로는 비로그인 시 로그인으로 연결한다', async ({
  page,
}) => {
  let complete: () => void = () => {}
  const wait = new Promise<void>((resolve) => {
    complete = resolve
  })
  await page.route('**/api/v1/me', async (route) => {
    await wait
    await route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } })
  })
  await page.goto('/login?returnTo=%2Flibrary')
  await expect(
    page.getByRole('status', { name: '로그인 확인 중' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: 'Google로 로그인' })).toHaveCount(
    0,
  )
  complete()
  await expect(
    page.getByText(
      '로그인 상태를 확인하지 못했어요. 잠시 후 다시 확인해 주세요.',
    ),
  ).toBeVisible()
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }),
  )
  await page.getByRole('button', { name: '로그인 상태 다시 확인' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.goto('/library?tab=favorites#saved')
  await expect(page).toHaveURL('/login')
  await expect(
    page.getByRole('link', { name: 'Google로 로그인' }),
  ).toBeVisible()
})

test('외부 복귀·로그인 루프를 막고 인증된 사용자는 안전한 보관함으로 이동한다', async ({
  page,
}) => {
  await page.route('**/api/v1/me', (route) => route.fulfill({ json: user }))
  for (const value of [
    'https://evil.example',
    '//evil.example',
    '/api/v1/me',
    '/login?returnTo=/login',
    '/%2e%2e/api',
  ]) {
    await page.goto(
      `/login?returnTo=${encodeURIComponent(value)}&loginResult=success`,
    )
    await expect(page).toHaveURL('/library')
    await expect(page.getByRole('heading', { name: '내 보관함' })).toBeVisible()
  }
})

test('로그아웃은 CSRF를 받아 한 번 전송하고 세션 만료 시 보호 화면을 숨긴다', async ({
  page,
}) => {
  let loggedIn = true
  let csrfCalls = 0
  let logoutCalls = 0
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (route) => {
    csrfCalls++
    return route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: `t${csrfCalls}` },
    })
  })
  await page.route('**/api/v1/auth/logout', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe(`t${csrfCalls}`)
    logoutCalls++
    loggedIn = false
    await route.fulfill({ status: 204 })
  })
  await page.goto('/library')
  await expect(page.getByRole('heading', { name: '내 보관함' })).toBeVisible()
  await accountMenu(page)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page).toHaveURL('/login')
  await expect(
    page.getByRole('link', { name: 'Google로 로그인' }),
  ).toBeVisible()
  expect(logoutCalls).toBe(1)
  expect(csrfCalls).toBe(1)
  await page.goBack()
  await expect(
    page.getByRole('heading', { name: '내 보관함', exact: true }),
  ).toHaveCount(0)
})

test('보호 화면의 복귀 경로는 주소에 노출하지 않고 새로고침 후에도 보관한다', async ({
  page,
}) => {
  await page.goto('/library/playlists')
  await expect(page).toHaveURL('/login')
  await expect(
    page.getByRole('heading', { name: '로그인', exact: true }),
  ).toBeVisible()
  expect(
    await page.evaluate(() => sessionStorage.getItem('stelody-login-return')),
  ).toBe('/library/playlists')
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '로그인', exact: true }),
  ).toBeVisible()
  await expect(page).toHaveURL('/login')
  expect(
    await page.evaluate(() => sessionStorage.getItem('stelody-login-return')),
  ).toBe('/library/playlists')
})

test('저장된 외부 주소를 신뢰하지 않고 기본 화면으로 복귀한 뒤 삭제한다', async ({
  page,
}) => {
  await page.addInitScript(() =>
    sessionStorage.setItem('stelody-login-return', 'https://evil.example'),
  )
  await page.route('**/api/v1/me', (route) => route.fulfill({ json: user }))
  await page.goto('/login')
  await expect(page).toHaveURL('/library')
  expect(
    await page.evaluate(() => sessionStorage.getItem('stelody-login-return')),
  ).toBeNull()
})

test('브라우저 저장소가 차단돼도 Google 로그인 후 기본 화면으로 복귀한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'sessionStorage', {
      get: () => {
        throw new Error('Storage blocked')
      },
    })
  })
  let loggedIn = false
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/google?*', (route) => {
    loggedIn = true
    return route.fulfill({
      status: 302,
      headers: { Location: '/login?loginResult=success' },
    })
  })
  await page.goto('/login?returnTo=%2Faccount')
  await expect(page).toHaveURL('/login')
  await page.getByRole('link', { name: 'Google로 로그인' }).click()
  await expect(page).toHaveURL('/library')
})

test('로그아웃 403은 자동 재전송하지 않고 다음 사용자 재시도에서 새 CSRF를 쓴다', async ({
  page,
}) => {
  let loggedIn = true
  let csrfCalls = 0
  const tokens: (string | undefined)[] = []
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: `fresh-${++csrfCalls}` },
    }),
  )
  await page.route('**/api/v1/auth/logout', async (route) => {
    tokens.push(route.request().headers()['x-csrf-token'])
    if (tokens.length === 1)
      await route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    else {
      loggedIn = false
      await route.fulfill({ status: 204 })
    }
  })
  await page.goto('/search')
  await accountMenu(page)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.',
  )
  expect(tokens).toEqual(['fresh-1'])
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(
    page.getByRole('button', { name: '로그아웃', exact: true }),
  ).toHaveCount(0)
  await expect.poll(() => tokens).toEqual(['fresh-1', 'fresh-2'])
})

test('다시 활성화한 화면에서 만료된 세션을 확인하면 보호 화면을 닫는다', async ({
  page,
}) => {
  let expired = false
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      expired
        ? { status: 401, json: { code: 'SESSION_EXPIRED' } }
        : { json: user },
    ),
  )
  await page.goto('/library')
  await expect(page.getByRole('heading', { name: '내 보관함' })).toBeVisible()
  await page.waitForLoadState('networkidle')
  expired = true
  await page.evaluate(() =>
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true })),
  )
  await expect(page).toHaveURL('/login')
  await expect(
    page.getByRole('heading', { name: '내 보관함', exact: true }),
  ).toHaveCount(0)
})

test('CSRF 조회 실패는 로그아웃을 전송하지 않으며 응답 유실 시 세션을 재확인한다', async ({
  page,
}) => {
  let loggedIn = true
  let csrfFails = true
  let logoutCalls = 0
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill(
      csrfFails
        ? { status: 503, json: { code: 'UNAVAILABLE' } }
        : { json: { headerName: 'X-CSRF-TOKEN', token: 'fresh' } },
    ),
  )
  await page.route('**/api/v1/auth/logout', async (route) => {
    logoutCalls++
    loggedIn = false
    await route.abort('failed')
  })
  await page.goto('/library')
  await accountMenu(page)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.',
  )
  expect(logoutCalls).toBe(0)
  csrfFails = false
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page).toHaveURL('/login')
  await expect(
    page.getByRole('link', { name: 'Google로 로그인' }),
  ).toBeVisible()
  expect(logoutCalls).toBe(1)
  await expect(
    page.getByText('로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.', {
      exact: true,
    }),
  ).toHaveCount(0)
})

for (const path of ['/search', '/library']) {
  test(`응답 유실 뒤 비로그인이 확인되면 로그아웃 오류를 남기지 않는다: ${path}`, async ({
    page,
  }) => {
    let loggedIn = true
    let logoutCalls = 0
    await page.route('**/api/v1/me', (route) =>
      route.fulfill(
        loggedIn
          ? { json: user }
          : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
      ),
    )
    await page.route('**/api/v1/auth/csrf', (route) =>
      route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
    )
    await page.route('**/api/v1/auth/logout', async (route) => {
      logoutCalls++
      loggedIn = false
      await route.abort('failed')
    })
    await page.goto(path)
    await accountMenu(page)
    await page.getByRole('button', { name: '로그아웃', exact: true }).click()
    await expect(page).toHaveURL(path === '/library' ? '/login' : path)
    await expect(
      page.getByRole('link', {
        name: path === '/library' ? 'Google로 로그인' : '로그인',
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByText(
        '로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.',
        { exact: true },
      ),
    ).toHaveCount(0)
    expect(logoutCalls).toBe(1)
  })
}

test('응답 유실 후 세션 조회도 실패하면 오류와 재시도 동작을 유지한다', async ({
  page,
}) => {
  let checkingFails = false
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      checkingFails
        ? { status: 503, json: { code: 'UNAVAILABLE' } }
        : { json: user },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
  )
  await page.route('**/api/v1/auth/logout', async (route) => {
    checkingFails = true
    await route.abort('failed')
  })
  await page.goto('/search')
  await accountMenu(page)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.',
  )
  await expect(
    page.getByRole('button', { name: '로그아웃', exact: true }),
  ).toBeEnabled()
})
