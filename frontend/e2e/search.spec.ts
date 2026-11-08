import { test, expect, makeSong, emptyPage } from './fixtures'

test('통합 헤더에서 검색하고 결과 카드·새로고침·뒤로 가기를 연결한다', async ({
  page,
}) => {
  const calls: URLSearchParams[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    const params = new URL(route.request().url()).searchParams
    calls.push(params)
    return route.fulfill({
      json: {
        ...emptyPage,
        items: [makeSong(1), makeSong(2), makeSong(3)],
        totalCount: 3,
      },
    })
  })
  await page.goto('/search')
  await expect(
    page.getByRole('heading', { name: '검색어를 입력해 주세요' }),
  ).toBeVisible()
  expect(calls).toHaveLength(0)
  await expect(page.getByRole('searchbox')).toBeVisible()
  await expect(page.getByRole('tab')).toHaveCount(0)
  await page.getByRole('searchbox').fill('노래 & cover?')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: "'노래 & cover?' 검색결과 3건" }),
  ).toBeVisible()
  expect(calls.at(-1)?.get('q')).toBe('노래 & cover?')
  expect(calls.at(-1)?.get('sort')).toBe('RELEVANCE')
  await expect(page.getByRole('article')).toHaveCount(3)
  await expect(page.getByRole('article').first()).toContainText('커버')
  const first = await page.getByRole('article').nth(0).boundingBox()
  const second = await page.getByRole('article').nth(1).boundingBox()
  const third = await page.getByRole('article').nth(2).boundingBox()
  expect(first?.y).toBe(second?.y)
  if ((page.viewportSize()?.width ?? 0) < 768)
    expect(third!.y).toBeGreaterThan(first!.y)
  else {
    expect(first?.width).toBe(200)
    expect(third?.y).toBe(first?.y)
  }
  await page.reload()
  await expect(page.getByRole('searchbox')).toHaveValue('노래 & cover?')
  await page.getByRole('searchbox').fill('다른 노래')
  await page.getByRole('searchbox').press('Enter')
  await expect(
    page.getByRole('heading', { name: "'다른 노래' 검색결과 3건" }),
  ).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('searchbox')).toHaveValue('노래 & cover?')
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await expect(
    page.getByRole('article').first().getByRole('link'),
  ).toHaveAttribute('href', `/songs/${makeSong(1).id}`)
})

test('빈 입력은 검색하지 않고 잘못된 URL은 새 검색으로 복구한다', async ({
  page,
}) => {
  let calls = 0
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    return route.fulfill({ json: emptyPage })
  })
  await page.goto('/search')
  await page.getByRole('searchbox').fill('   ')
  await page.getByRole('searchbox').press('Enter')
  expect(calls).toBe(0)
  await page.goto('/search?type=WRONG')
  await expect(
    page.getByText('검색 조건을 확인해 주세요', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('searchbox').fill('없는 노래')
  await page.getByRole('searchbox').press('Enter')
  await expect(
    page.getByRole('heading', {
      name: '검색결과가 없습니다.',
      exact: true,
    }),
  ).toBeVisible()
  expect(calls).toBe(1)
})

test('검색 오류 재시도와 메뉴의 테마·로그인 복귀 경로를 지원한다', async ({
  page,
}) => {
  let fail = true
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill(
      fail
        ? { status: 500, json: { code: 'UNAVAILABLE' } }
        : { json: { ...emptyPage, items: [makeSong(1)], totalCount: 1 } },
    ),
  )
  await page.goto('/search?q=노래')
  await expect(
    page.getByText('검색 결과를 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  fail = false
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(1)
  await expect(
    page.getByRole('banner').getByRole('link', { name: '로그인', exact: true }),
  ).toHaveAttribute('href', /returnTo=/)
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '다크 모드로 변경' }).click()
  await page.keyboard.press('Escape')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByRole('searchbox')).toHaveValue('노래')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByRole('article')).toHaveCount(1)
})

test('실패한 다음 페이지를 같은 검색어와 필터로 재시도한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  const cursors: (string | null)[] = []
  let fail = true
  await page.route('**/api/v1/songs?*', (route) => {
    const p = new URL(route.request().url()).searchParams
    cursors.push(p.get('cursor'))
    expect(p.get('mode')).toBe('SEARCH')
    expect(p.get('type')).toBe('COVER')
    expect(p.get('q')).toBe('노래')
    expect(p.get('startDate')).toBe('2025-01-01')
    expect(p.get('endDate')).toBe('2025-12-31')
    if (p.has('cursor') && fail)
      return route.fulfill({
        status: 400,
        json: { code: 'INVALID_CATALOG_QUERY' },
      })
    return route.fulfill({
      json: p.has('cursor')
        ? { ...emptyPage, items: [makeSong(20), makeSong(21)], totalCount: 21 }
        : {
            items: Array.from({ length: 20 }, (_, i) => makeSong(i + 1)),
            hasNext: true,
            nextCursor: 'next+/=&cursor',
            totalCount: 21,
          },
    })
  })
  await page.goto(
    '/search?q=노래&type=COVER&startDate=2025-01-01&endDate=2025-12-31',
  )
  await expect(page.getByRole('article')).toHaveCount(20)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByText('검색 결과를 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(20)
  fail = false
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(21)
  expect(cursors).toEqual([null, 'next+/=&cursor', 'next+/=&cursor'])
})

test('이전 검색이 늦게 응답해도 새 검색 결과를 덮어쓰지 않는다', async ({
  page,
}) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let oldRequested = false
  let oldAborted = false
  page.on('requestfailed', (request) => {
    if (new URL(request.url()).searchParams.get('q') === 'old')
      oldAborted = true
  })
  await page.route('**/api/v1/songs?*', async (route) => {
    const q = new URL(route.request().url()).searchParams.get('q')
    if (q === 'old') {
      oldRequested = true
      await gate
    }
    await route
      .fulfill({
        json: {
          ...emptyPage,
          items: [
            makeSong(1, { title: q === 'old' ? '이전 결과' : '새 검색 결과' }),
          ],
          totalCount: 1,
        },
      })
      .catch(() => {})
  })
  try {
    await page.goto('/search?q=old')
    await expect.poll(() => oldRequested).toBe(true)
    await expect(
      page.getByRole('status', { name: '노래 불러오는 중' }),
    ).toBeVisible()
    await page.getByRole('searchbox').fill('new')
    await page.getByRole('button', { name: '검색', exact: true }).click()
    await expect(page.getByText('새 검색 결과', { exact: true })).toBeVisible()
    await expect.poll(() => oldAborted).toBe(true)
    release()
    await expect(page.getByText('이전 결과', { exact: true })).toHaveCount(0)
  } finally {
    release()
  }
})

test('잘못된 날짜 URL은 차단하고 서버 날짜 오류는 필드별로 안내한다', async ({
  page,
}) => {
  let calls = 0
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    return route.fulfill({
      status: 400,
      json: {
        code: 'INVALID_CATALOG_QUERY',
        fieldErrors: [
          {
            field: 'startDate',
            code: 'INVALID_DATE_RANGE',
            message: 'invalid range',
          },
          {
            field: 'endDate',
            code: 'INVALID_DATE_RANGE',
            message: 'invalid range',
          },
        ],
      },
    })
  })
  await page.goto('/search?q=노래&startDate=2025-02-30')
  await expect(
    page.getByText('YYYY-MM-DD 형식의 올바른 날짜를 입력해 주세요.', {
      exact: true,
    }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.goto('/search?q=노래&startDate=2025-09-01&endDate=2025-09-30')
  const errors = page.getByRole('alert', { name: '검색 조건 오류' })
  await expect(errors).toContainText(
    '시작일: 시작일은 종료일보다 늦을 수 없습니다.',
  )
  await expect(errors).toContainText(
    '종료일: 시작일은 종료일보다 늦을 수 없습니다.',
  )
  expect(calls).toBe(1)
})

test('조회수 집계 변경 오류는 기존 커서를 버리고 첫 페이지부터 재시작한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  const cursors: (string | null)[] = []
  let publicationChanged = false
  await page.route('**/api/v1/songs?*', (route) => {
    const params = new URL(route.request().url()).searchParams
    const cursor = params.get('cursor')
    cursors.push(cursor)
    expect(params.get('sort')).toBe('VIEWS')
    expect(params.get('q')).toBe('노래')
    expect(params.get('type')).toBe('COVER')
    expect(params.get('startDate')).toBe('2025-01-01')
    if (cursor === 'old-third') {
      publicationChanged = true
      return route.fulfill({
        status: 409,
        json: { code: 'VIEW_PUBLICATION_CHANGED' },
      })
    }
    if (publicationChanged) {
      if (cursor && cursor !== 'new-next')
        return route.fulfill({
          status: 409,
          json: { code: 'VIEW_PUBLICATION_CHANGED' },
        })
      return route.fulfill({
        json: cursor
          ? { ...emptyPage, items: [makeSong(102)], totalCount: 2 }
          : {
              items: [makeSong(101)],
              totalCount: 2,
              hasNext: true,
              nextCursor: 'new-next',
            },
      })
    }
    return route.fulfill({
      json: {
        items: [makeSong(cursor ? 2 : 1)],
        totalCount: 3,
        hasNext: true,
        nextCursor: cursor ? 'old-third' : 'old-next',
      },
    })
  })
  await page.goto('/search?q=노래&type=COVER&sort=VIEWS&startDate=2025-01-01')
  await expect(page.getByRole('article')).toHaveCount(1)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(2)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByText('검색 결과를 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(2)
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: "'노래' 검색결과 2건", exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(cursors).toEqual([null, 'old-next', 'old-third', null])
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(2)
  expect(cursors).toEqual([null, 'old-next', 'old-third', null, 'new-next'])
})
