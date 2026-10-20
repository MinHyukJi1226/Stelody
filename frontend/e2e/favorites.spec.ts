import type { Page } from '@playwright/test'
import { test, expect, makeSong, emptyPage } from './fixtures'
import { detail, makeViews, song } from './song-detail-fixtures'
import type { FavoriteItem } from '../src/features/favorites/api'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const second = makeSong(2)
const unavailableId = makeSong(3).id
const item = (value = song): FavoriteItem => ({
  songId: value.id,
  song: value,
  available: true,
  savedAt: '2026-10-20T01:00:00Z',
  unavailableMessage: null,
})

async function mockFavorites(page: Page, initial: FavoriteItem[] = []) {
  let items = initial
  let loggedIn = true
  let error = 0
  let responseLost = false
  let csrf = 0
  const writes: { method: string; id: string; token: string | undefined }[] = []
  await page.route('**/api/v1/me', (route) =>
    route.fulfill(
      loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: `csrf-${++csrf}` },
    }),
  )
  await page.route('**/api/v1/me/favorites**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const id = url.pathname.split('/')[5]
    if (url.pathname === '/api/v1/me/favorites') {
      expect(url.searchParams.has('sort')).toBe(false)
      await route.fulfill({
        json: {
          ...emptyPage,
          items,
          totalCount: items.length,
          availableCount: items.filter((i) => i.available).length,
        },
      })
    } else if (request.method() === 'GET') {
      await route.fulfill({
        json: { songId: id, favorited: items.some((i) => i.songId === id) },
      })
    } else {
      writes.push({
        method: request.method(),
        id: id!,
        token: request.headers()['x-csrf-token'],
      })
      if (error) {
        await route.fulfill({
          status: error,
          json: {
            code:
              error === 409
                ? 'FAVORITE_LIMIT_REACHED'
                : error === 401
                  ? 'SESSION_EXPIRED'
                  : 'ACCESS_DENIED',
          },
        })
        return
      }
      if (request.method() === 'PUT' && !items.some((i) => i.songId === id))
        items = [item(id === song.id ? song : second), ...items]
      if (request.method() === 'DELETE')
        items = items.filter((i) => i.songId !== id)
      if (responseLost) await route.abort('failed')
      else await route.fulfill({ status: 204 })
    }
  })
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: [song], totalCount: 1 } }),
  )
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: detail }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({ json: makeViews() }),
  )
  return {
    writes,
    setLoggedIn(value: boolean) {
      loggedIn = value
    },
    setError(value: number) {
      error = value
    },
    loseResponse() {
      responseLost = true
    },
  }
}

async function accountMenu(page: Page) {
  if ((page.viewportSize()?.width ?? 0) < 768)
    await page.getByRole('button', { name: '메뉴 열기' }).click()
}

test('보관함은 저장순·요약·이용 불가 항목을 표시하고 삭제 후 합계와 빈 화면을 갱신한다', async ({
  page,
}) => {
  const mock = await mockFavorites(page, [
    item(),
    {
      songId: unavailableId,
      song: null,
      available: false,
      savedAt: '2026-10-19T01:00:00Z',
      unavailableMessage: '저장한 항목은 유지됩니다.',
    },
  ])
  await page.goto('/library')
  await expect(page.getByText('저장 2곡 · 이용 가능 1곡')).toBeVisible()
  await expect(page.getByLabel('정렬: 최근 저장순')).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '현재 이용할 수 없는 곡' }),
  ).toBeVisible()
  expect(await page.locator('article').first().innerText()).toContain(
    song.title,
  )
  await expect(page.locator('article').nth(1).getByRole('link')).toHaveCount(0)
  await page.getByRole('button', { name: '이용 불가 곡 즐겨찾기 해제' }).click()
  await expect(page.getByText('저장 1곡 · 이용 가능 1곡')).toBeVisible()
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 해제` })
    .click()
  await expect(
    page.getByRole('heading', { name: '아직 저장한 노래가 없습니다' }),
  ).toBeVisible()
  expect(mock.writes.map((w) => [w.method, w.id])).toEqual([
    ['DELETE', unavailableId],
    ['DELETE', song.id],
  ])
  expect(mock.writes.every((w) => w.token === 'csrf-1')).toBe(true)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('일반 카드는 상세 링크만 제공하고 상세에서 저장한 곡은 보관함에 반영된다', async ({
  page,
}) => {
  const mock = await mockFavorites(page)
  let stateReads = 0
  await page.route('**/api/v1/me/favorites/*', async (route) => {
    if (route.request().method() === 'GET') stateReads++
    await route.fallback()
  })
  for (const path of ['/', '/songs', '/search?q=test']) {
    await page.goto(path)
    await expect(
      page.getByRole('link', { name: `${song.title} 상세 보기`, exact: true }),
    ).toBeVisible()
    await expect(page.locator('article').getByRole('button')).toHaveCount(0)
  }
  expect(stateReads).toBe(0)
  await page
    .getByRole('link', { name: `${song.title} 상세 보기`, exact: true })
    .click()
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 저장`, exact: true })
    .click()
  const remove = page.getByRole('button', {
    name: `${song.title} 즐겨찾기 해제`,
    exact: true,
  })
  await expect(remove).toHaveAttribute('aria-pressed', 'true')
  await page.reload()
  await expect(remove).toBeEnabled()
  await accountMenu(page)
  await page.getByRole('link', { name: '내 보관함', exact: true }).click()
  await expect(page.getByText('저장 1곡 · 이용 가능 1곡')).toBeVisible()
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 해제`, exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '아직 저장한 노래가 없습니다' }),
  ).toBeVisible()
  expect(mock.writes.map((w) => w.method)).toEqual(['PUT', 'DELETE'])
})

test('비로그인 저장은 Google 복귀 후 한 번만 실행하며 새로고침으로 재실행하지 않는다', async ({
  page,
}) => {
  const mock = await mockFavorites(page)
  mock.setLoggedIn(false)
  await page.route('**/api/v1/auth/google?*', async (route) => {
    expect(route.request().isNavigationRequest()).toBe(true)
    mock.setLoggedIn(true)
    const target = new URL(
      new URL(route.request().url()).searchParams.get('returnTo')!,
      page.url(),
    )
    target.searchParams.set('loginResult', 'success')
    await route.fulfill({
      status: 302,
      headers: { Location: target.pathname + target.search },
    })
  })
  await page.goto(`/songs/${song.id}?from=home#info`)
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 저장`, exact: true })
    .click()
  await expect(page).toHaveURL(
    `/login?returnTo=%2Fsongs%2F${song.id}%3Ffrom%3Dhome%23info`,
  )
  expect(mock.writes).toHaveLength(0)
  await page.getByRole('link', { name: 'Google로 로그인', exact: true }).click()
  await expect(page).toHaveURL(`/songs/${song.id}?from=home#info`)
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  expect(mock.writes).toHaveLength(1)
  await page.reload()
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  expect(mock.writes).toHaveLength(1)
  expect(
    await page.evaluate(() => sessionStorage.getItem('stelody-login-action')),
  ).toBeNull()
})

test('로그인 취소 후엔 자동 저장하지 않으며 이후 성공한 로그인에서 재개한다', async ({
  page,
}) => {
  const mock = await mockFavorites(page)
  mock.setLoggedIn(false)
  let cancelled = true
  await page.route('**/api/v1/auth/google?*', async (route) => {
    const target = new URL(
      new URL(route.request().url()).searchParams.get('returnTo')!,
      page.url(),
    )
    target.searchParams.set('loginResult', cancelled ? 'cancelled' : 'success')
    if (!cancelled) mock.setLoggedIn(true)
    await route.fulfill({
      status: 302,
      headers: { Location: target.pathname + target.search },
    })
  })
  await page.goto(`/songs/${song.id}`)
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 저장`, exact: true })
    .click()
  await page.getByRole('link', { name: 'Google로 로그인' }).click()
  await expect(
    page.getByText('로그인이 취소되었어요. 다시 시도할 수 있어요.'),
  ).toBeVisible()
  expect(mock.writes).toHaveLength(0)
  cancelled = false
  await page.getByRole('link', { name: 'Google로 로그인' }).click()
  await expect(page).toHaveURL(`/songs/${song.id}`)
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  expect(mock.writes).toHaveLength(1)
})

test('403·한도 초과는 저장 상태를 바꾸지 않으며 수동 재시도에 새 CSRF를 사용한다', async ({
  page,
}) => {
  const mock = await mockFavorites(page)
  mock.setError(403)
  await page.goto(`/songs/${song.id}`)
  const save = page.getByRole('button', {
    name: `${song.title} 즐겨찾기 저장`,
    exact: true,
  })
  await save.click()
  await expect(page.getByRole('alert')).toHaveText(
    '즐겨찾기를 변경할 수 없어요. 잠시 후 다시 시도해 주세요.',
  )
  expect(mock.writes).toHaveLength(1)
  mock.setError(409)
  await save.click()
  await expect(page.getByRole('alert')).toHaveText(
    '즐겨찾기 저장 한도에 도달했어요. 보관함에서 곡을 정리해 주세요.',
  )
  expect(mock.writes.map((w) => w.token)).toEqual(['csrf-1', 'csrf-2'])
  await expect(save).toHaveAttribute('aria-pressed', 'false')
  mock.setError(0)
  await save.click()
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  expect(mock.writes).toHaveLength(3)
})

test('응답 유실 뒤 상태 조회로 저장이 확인되면 중복 전송 없이 성공 처리한다', async ({
  page,
}) => {
  const mock = await mockFavorites(page)
  mock.loseResponse()
  await page.goto(`/songs/${song.id}`)
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 저장`, exact: true })
    .click()
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(mock.writes).toHaveLength(1)
})

test('상세 버튼은 저장 처리 중 잠그고 한 번만 변경한다', async ({ page }) => {
  const mock = await mockFavorites(page)
  let release: () => void = () => {}
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(`**/api/v1/me/favorites/${song.id}`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fallback()
      return
    }
    await wait
    await route.fallback()
  })
  await page.goto(`/songs/${song.id}`)
  const button = page.getByRole('button', {
    name: `${song.title} 즐겨찾기 저장`,
    exact: true,
  })
  await button.click()
  await expect(button).toBeDisabled()
  release()
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 해제`,
      exact: true,
    }),
  ).toBeEnabled()
  expect(mock.writes).toHaveLength(1)
})

test('목록 로딩·장애·재시도를 구분하고 403은 캐시된 개인 목록을 숨긴다', async ({
  page,
}) => {
  await mockFavorites(page, [item()])
  let release: () => void = () => {}
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  let status = 503
  await page.route('**/api/v1/me/favorites?*', async (route) => {
    await wait
    if (status) await route.fulfill({ status, json: { code: 'ACCESS_DENIED' } })
    else await route.fallback()
  })
  await page.goto('/library')
  await expect(
    page.getByRole('status', { name: '즐겨찾기 불러오는 중' }),
  ).toBeVisible()
  release()
  await expect(
    page.getByRole('heading', { name: '불러오지 못했어요' }),
  ).toBeVisible()
  status = 0
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await page.waitForLoadState('networkidle')
  status = 403
  await page.clock.install()
  await page.clock.fastForward(31_000)
  await page.evaluate(() =>
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true })),
  )
  await expect(
    page.getByRole('heading', { name: '이 화면에 접근할 수 없습니다' }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toHaveCount(0)
})

test('목록 페이지의 중복 곡은 한 번 표시하고 잘못된 커서는 처음부터 재조회한다', async ({
  page,
}) => {
  await mockFavorites(page, [item()])
  await page.addInitScript(() => {
    Reflect.deleteProperty(window, 'IntersectionObserver')
  })
  const cursors: (string | null)[] = []
  let broken = true
  await page.route('**/api/v1/me/favorites?*', async (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    cursors.push(cursor)
    if (cursor && broken) {
      await route.fulfill({
        status: 400,
        json: { code: 'INVALID_FAVORITE_QUERY' },
      })
      return
    }
    await route.fulfill({
      json: {
        items: cursor ? [item(), item(second)] : [item()],
        totalCount: 2,
        availableCount: 2,
        hasNext: !cursor,
        nextCursor: cursor ? null : 'next',
      },
    })
  })
  await page.goto('/library')
  const more = page.getByRole('button', {
    name: '즐겨찾기 더 보기',
    exact: true,
  })
  await more.click()
  await expect(
    page.getByText('목록을 처음부터 다시 불러와 주세요.'),
  ).toBeVisible()
  broken = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await more.click()
  await expect(
    page.getByRole('heading', { name: second.title, exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toHaveCount(1)
  expect(cursors.filter((c) => c === null)).toHaveLength(2)
})

test('변경 중 세션이 만료되면 로그인으로 이동하고 개인 목록을 숨긴다', async ({
  page,
}) => {
  const mock = await mockFavorites(page, [item()])
  await page.goto('/library')
  await expect(
    page.getByRole('button', { name: `${song.title} 즐겨찾기 해제` }),
  ).toBeEnabled()
  mock.setError(401)
  mock.setLoggedIn(false)
  await page
    .getByRole('button', { name: `${song.title} 즐겨찾기 해제` })
    .click()
  await expect(page).toHaveURL('/login?returnTo=%2Flibrary')
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toHaveCount(0)
  expect(mock.writes).toHaveLength(1)
  await expect(
    page.getByRole('link', { name: 'Google로 로그인' }),
  ).toBeVisible()
})

test('보관함의 플레이리스트 탭은 준비 중 화면으로 연결한다', async ({
  page,
}) => {
  await mockFavorites(page, [item()])
  await page.goto('/library')
  await page.getByRole('link', { name: '플레이리스트', exact: true }).click()
  await expect(page).toHaveURL('/library/playlists')
  await expect(
    page.getByText('플레이리스트 기능은 준비 중입니다.', { exact: true }),
  ).toBeVisible()
  await accountMenu(page)
  await expect(
    page.getByRole('button', { name: '로그아웃', exact: true }),
  ).toBeVisible()
})
