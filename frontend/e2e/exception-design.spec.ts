import type { Page, TestInfo } from '@playwright/test'
import { emptyPage, expect, makeSong, test } from './fixtures'

async function capture(page: Page, info: TestInfo, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await expect(page.getByRole('main')).toHaveCount(1)
  await expect(page.getByRole('banner').getByRole('search')).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

for (const scheme of ['light', 'dark'] as const) {
  test.describe(scheme, () => {
    test.beforeEach(async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme })
    })

    test('렌더링 오류도 내부 오류 원문 대신 공통 화면을 표시하고 재시도로 복구한다', async ({
      page,
    }, info) => {
      let invalid = true
      await page.route('**/api/v1/members?*', (route) =>
        route.fulfill({
          json: invalid ? { ...emptyPage, items: null } : emptyPage,
        }),
      )
      await page.goto('/members')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '화면을 불러오지 못했습니다',
      )
      await expect(page.getByText('Unexpected Application Error!')).toHaveCount(
        0,
      )
      await expect(page.locator('main footer')).toHaveCount(1)
      await capture(page, info, `route-error-${scheme}`)
      invalid = false
      await page.getByRole('button', { name: '다시 시도', exact: true }).click()
      await expect(
        page.getByRole('heading', { name: '전체 멤버', exact: true }),
      ).toBeVisible()
    })

    test('긴 검색어가 있는 오류 화면도 검색 조건을 보존하며 화면을 넘치지 않는다', async ({
      page,
    }, info) => {
      const query = 'long-search-'.repeat(16)
      await page.route('**/api/v1/songs?*', (route) =>
        route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }),
      )
      await page.goto(`/search?q=${query}`)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '검색 결과를 불러오지 못했습니다',
      )
      await expect(page.getByRole('searchbox')).toHaveValue(query)
      await capture(page, info, `search-error-long-${scheme}`)
    })

    test('404도 공통 검색 헤더·푸터를 제공하고 키보드로 홈에 돌아간다', async ({
      page,
    }, info) => {
      await page.goto('/unknown-page')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '페이지를 찾을 수 없습니다',
      )
      await expect(page.locator('main footer')).toHaveCount(1)
      const home = page
        .getByRole('main')
        .getByRole('link', { name: '홈으로 돌아가기' })
      await expect(page.getByRole('main').getByRole('link')).toHaveCount(1)
      await capture(page, info, `not-found-${scheme}`)
      await home.focus()
      await page.keyboard.press('Enter')
      await expect(page).toHaveURL('/')
    })

    test('로그인 상태 조회 오류는 보호 내용을 숨기고 다시 확인할 수 있다', async ({
      page,
    }, info) => {
      let failed = true
      await page.route('**/api/v1/me', (route) =>
        route.fulfill({
          status: failed ? 503 : 401,
          json: { code: failed ? 'UNAVAILABLE' : 'AUTHENTICATION_REQUIRED' },
        }),
      )
      await page.goto('/library/playlists')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '로그인 상태를 확인하지 못했습니다',
      )
      await expect(page.locator('main footer')).toHaveCount(1)
      await expect(page.getByRole('main').getByRole('link')).toHaveCount(0)
      await capture(page, info, `session-error-${scheme}`)
      failed = false
      await page.getByRole('button', { name: '다시 시도', exact: true }).click()
      await expect(page).toHaveURL('/login')
      await expect(
        page.getByRole('heading', { name: '로그인', exact: true }),
      ).toBeVisible()
    })

    test('짧은 주소 조회의 접근 불가 화면도 페이지 여백·푸터와 복귀 링크를 유지한다', async ({
      page,
    }, info) => {
      await page.route('**/api/v1/me', (route) =>
        route.fulfill({
          json: {
            id: makeSong(800).id,
            email: 'test@example.com',
            role: 'USER',
            status: 'ACTIVE',
          },
        }),
      )
      await page.goto('/library/playlists/Abc123XyZ9')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '이 목록에 접근할 수 없습니다',
      )
      await expect(page.locator('main footer')).toHaveCount(1)
      await expect(
        page
          .getByRole('main')
          .getByRole('link', { name: '내 플레이리스트', exact: true }),
      ).toHaveAttribute('href', '/library/playlists')
      await capture(page, info, `playlist-denied-${scheme}`)
    })

    test('전체곡 오류 안내에서 재시도하면 목록을 다시 조회한다', async ({
      page,
    }, info) => {
      let failed = true
      await page.route('**/api/v1/songs?*', (route) =>
        route.fulfill(
          failed
            ? { status: 503, json: { code: 'UNAVAILABLE' } }
            : { json: emptyPage },
        ),
      )
      await page.goto('/songs')
      await expect(
        page.getByText('노래를 불러오지 못했습니다', { exact: true }),
      ).toBeVisible()
      await capture(page, info, `catalog-error-${scheme}`)
      failed = false
      await page.getByRole('button', { name: '다시 시도', exact: true }).click()
      await expect(
        page.getByText('아직 공개된 노래가 없습니다', { exact: true }),
      ).toBeVisible()
    })

    test('노래 접근 불가 안내는 공통 상태 카드를 쓰며 기존 전체곡 복귀만 제공한다', async ({
      page,
    }, info) => {
      await page.goto('/songs/invalid')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(
        '노래를 찾을 수 없습니다',
      )
      await expect(page.getByRole('main').getByRole('link')).toHaveCount(1)
      await expect(
        page.getByRole('link', { name: '전체곡 보기', exact: true }),
      ).toHaveAttribute('href', '/songs')
      await capture(page, info, `song-denied-${scheme}`)
    })
  })
}
