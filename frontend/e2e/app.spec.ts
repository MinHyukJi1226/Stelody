import { expect, test } from './fixtures'

test('직접 접근한 주소와 알 수 없는 경로에서 홈으로 복귀한다', async ({
  page,
}) => {
  await page.goto('/')
  await expect(
    page.getByRole('heading', { name: /좋아하는 목소리/ }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: /좋아하는 목소리/ }),
  ).toBeVisible()
  await page.goto('/unknown-page')
  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
  ).toBeVisible()
  await page.goto('/__ui')
  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
  ).toBeVisible()
  await page.getByRole('link', { name: '홈으로 돌아가기' }).focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/')
  await expect(
    page.getByRole('heading', { name: /좋아하는 목소리/ }),
  ).toBeVisible()
})

test('공통 메뉴와 브랜드 링크가 이동하고 화면이 넘치지 않는다', async ({
  page,
}) => {
  await page.goto('/')
  await page.evaluate(() => document.fonts.ready)
  const mobile = (page.viewportSize()?.width ?? 0) < 768
  const menu = page.getByRole('button', { name: '메뉴 열기' })
  if (mobile) {
    const themeButton = page.getByRole('button', { name: /모드로 변경$/ })
    const themeBounds = await themeButton.boundingBox()
    const menuBounds = await menu.boundingBox()
    expect(menuBounds?.width).toBe(44)
    expect(menuBounds?.height).toBe(44)
    expect(themeBounds?.x).toBeLessThan(menuBounds!.x)
    expect((await page.locator('header').boundingBox())?.height).toBe(93)
    await expect(menu).toHaveCSS('border-top-width', '0px')
    await expect(menu).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect(menu).toHaveText('')
    await expect(menu.locator('.menu-icon')).not.toHaveCSS('mask-image', 'none')
    await menu.focus()
    await page.keyboard.press('Enter')
    await expect(
      page.getByRole('dialog', { name: '사이트 메뉴' }),
    ).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(menu).toBeFocused()
    await expect(page.getByRole('dialog')).toHaveCount(0)
  }
  const navigation = page.getByRole('navigation', {
    name: mobile ? '모바일 주 메뉴' : '주 메뉴',
    exact: true,
  })
  for (const label of ['검색', '멤버', '내 보관함', '로그인']) {
    if (mobile) await menu.click()
    const link = navigation.getByRole('link', { name: label, exact: true })
    await link.click()
    await expect(
      page.getByRole('heading', {
        name:
          label === '검색'
            ? '노래 검색'
            : label === '멤버'
              ? '목소리로 찾아보세요'
              : label === '내 보관함' || label === '로그인'
                ? '나만의 노래를 모아보세요'
                : label,
        exact: true,
      }),
    ).toBeVisible()
    if (mobile) await expect(page.getByRole('dialog')).toHaveCount(0)
    else if (label === '검색' || label === '멤버')
      await expect(link).toHaveAttribute('aria-current', 'page')
    if (label === '내 보관함' || label === '로그인') {
      await expect(
        page.getByRole('link', { name: 'Google로 로그인' }),
      ).toBeVisible()
    }
  }
  if (mobile) {
    const originalViewport = page.viewportSize()!
    await menu.click()
    await expect(
      page.getByRole('dialog', { name: '사이트 메뉴' }),
    ).toBeVisible()
    await page.setViewportSize({ width: 1024, height: 768 })
    // CSS hides the menu before the media-query subscription unmounts it.
    await expect(menu).not.toBeAttached()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.setViewportSize(originalViewport)
    await expect(menu).toBeEnabled()
    await expect(page.getByRole('dialog')).toHaveCount(0)
  }
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('main')).toHaveCount(1)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  const images = page.locator('header img')
  await expect(images).toHaveCount(2)
  for (const img of await images.all()) {
    expect(
      await img.evaluate(
        (node: HTMLImageElement) => node.complete && node.naturalWidth > 0,
      ),
    ).toBe(true)
  }
  await expect(page.getByRole('button', { name: /모드로 변경$/ })).toBeEnabled()
})

test('시스템 테마를 따르고 사용자가 선택한 테마는 새로고침 후 유지한다', async ({
  page,
}) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page
    .getByRole('button', { name: '다크 모드로 변경', exact: true })
    .click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page
    .getByRole('button', { name: '라이트 모드로 변경', exact: true })
    .click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  const icon = page.locator('header img').last()
  expect(
    await icon.evaluate(
      (n: HTMLImageElement) => n.complete && n.naturalWidth > 0,
    ),
  ).toBe(true)
})

test('브라우저 저장소가 차단되어도 테마 전환을 사용할 수 있다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(Storage.prototype, 'getItem', {
      value() {
        throw new Error('Storage blocked')
      },
    })
    Object.defineProperty(Storage.prototype, 'setItem', {
      value() {
        throw new Error('Storage blocked')
      },
    })
  })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.goto('/')
  await page
    .getByRole('button', { name: '다크 모드로 변경', exact: true })
    .click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(
    page.getByRole('heading', { name: /좋아하는 목소리/ }),
  ).toBeVisible()
})

test('키보드로 본문을 건너뛴다', async ({ page }) => {
  await page.goto('/')
  await page.keyboard.press('Tab')
  const skip = page.getByRole('link', { name: '본문으로 건너뛰기' })
  await expect(skip).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
})
