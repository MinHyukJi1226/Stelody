import { test, expect, makeSong, emptyPage } from './fixtures'

const memberA = '00000000-0000-4000-8000-000000000001'
const memberB = '00000000-0000-4000-8000-000000000002'

test('초기 화면에서 조건을 편집하고 검색하면 URL·요청·결과를 연결한다', async ({
  page,
}) => {
  let calls = 0
  let submitted: URLSearchParams | undefined
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    submitted = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: { ...emptyPage, items: [makeSong(1)], totalCount: 1 },
    })
  })
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({
      json: {
        items: [
          { id: memberA, name: '첫 멤버', activityStatus: 'ACTIVE' },
          { id: memberB, name: '졸업 멤버', activityStatus: 'GRADUATED' },
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  await page.goto('/search')
  await expect(
    page.getByRole('heading', { name: '노래 검색', exact: true }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await expect(page.getByLabel('시작일', { exact: true })).toBeEnabled()
  await page.getByRole('searchbox').fill('노래 & cover?')
  await page.getByRole('tab', { name: '커버', exact: true }).click()
  await page
    .getByRole('button', { name: '멤버 전체 멤버', exact: true })
    .click()
  const dialog = page.getByRole('dialog', { name: '멤버 선택' })
  await dialog.getByRole('checkbox', { name: '첫 멤버', exact: true }).check()
  await dialog
    .getByRole('checkbox', { name: '졸업 멤버 (졸업)', exact: true })
    .check()
  await dialog.getByRole('button', { name: '선택 완료' }).click()
  await page.getByLabel('시작일', { exact: true }).fill('2025-09-01')
  await page.getByLabel('종료일', { exact: true }).fill('2025-09-30')
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(
    page.getByRole('heading', { name: '검색 결과 1곡' }),
  ).toBeVisible()
  expect(submitted?.get('q')).toBe('노래 & cover?')
  expect(submitted?.get('mode')).toBe('SEARCH')
  expect(submitted?.get('type')).toBe('COVER')
  expect(submitted?.getAll('memberIds')).toEqual([memberA, memberB])
  expect(submitted?.get('sort')).toBe('RELEVANCE')
  expect(submitted?.get('startDate')).toBe('2025-09-01')
  expect(submitted?.get('endDate')).toBe('2025-09-30')
  await page.reload()
  await expect(page.getByRole('searchbox')).toHaveValue('노래 & cover?')
  await expect(page.getByLabel('시작일', { exact: true })).toHaveValue(
    '2025-09-01',
  )
  await expect(page.getByLabel('종료일', { exact: true })).toHaveValue(
    '2025-09-30',
  )
  await expect(
    page.getByRole('tab', { name: '커버', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('정렬과 뒤로 가기·초기화가 URL의 검색 조건을 복원한다', async ({
  page,
}) => {
  const calls: URLSearchParams[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    calls.push(new URL(route.request().url()).searchParams)
    return route.fulfill({
      json: { ...emptyPage, items: [makeSong(1)], totalCount: 1 },
    })
  })
  await page.goto('/search?q=노래&type=ORIGINAL')
  await expect(page.getByRole('article')).toHaveCount(1)
  await page.getByLabel('검색 결과 정렬', { exact: true }).selectOption('VIEWS')
  await expect.poll(() => calls.at(-1)?.get('sort')).toBe('VIEWS')
  await page.goBack()
  await expect(page.getByLabel('검색 결과 정렬', { exact: true })).toHaveValue(
    'RELEVANCE',
  )
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(page).toHaveURL('/search')
  await expect(page.getByRole('searchbox')).toHaveValue('')
  await expect(page.getByRole('article')).toHaveCount(0)
  await expect(
    page.getByRole('tab', { name: '전체', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
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
    await page.getByRole('button', { name: '검색 결과 보기' }).click()
    await expect(page.getByText('새 검색 결과', { exact: true })).toBeVisible()
    await expect.poll(() => oldAborted).toBe(true)
    release()
    await expect(page.getByText('이전 결과', { exact: true })).toHaveCount(0)
  } finally {
    release()
  }
})

test('잘못된 URL은 조회를 막고 초기화로 복구한다', async ({ page }) => {
  let calls = 0
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    return route.fulfill({ json: emptyPage })
  })
  await page.goto('/search?type=WRONG')
  await expect(
    page.getByText('검색 조건을 확인해 주세요', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(page).toHaveURL('/search')
  await expect(
    page.getByText('검색 조건을 확인해 주세요', { exact: true }),
  ).toHaveCount(0)
  await page.getByRole('searchbox').fill('없는 노래')
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(
    page.getByText('찾은 노래가 없습니다', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(1)
})

test('검색 실패는 재시도로 회복하고 멤버 조회 실패가 검색을 막지 않는다', async ({
  page,
}) => {
  let fail = true
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({ status: 400, json: { code: 'UNAVAILABLE' } }),
  )
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill(
      fail
        ? { status: 400, json: { code: 'INVALID_CATALOG_QUERY' } }
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
  await page
    .getByRole('button', { name: '멤버 전체 멤버', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '멤버 선택' })).toContainText(
    '멤버를 불러오지 못했습니다.',
  )
})

test('검색어와 필터가 모두 비어 있으면 검색하지 않는다', async ({ page }) => {
  let calls = 0
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    return route.fulfill({ json: emptyPage })
  })
  await page.goto('/search')
  const button = page.getByRole('button', {
    name: '검색 결과 보기',
    exact: true,
  })
  const input = page.getByRole('searchbox')
  await expect(button).toBeDisabled()
  await input.fill('아직 제출하지 않은 검색어')
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(input).toHaveValue('')
  await expect(button).toBeDisabled()
  await input.press('Enter')
  await input.fill('   ')
  await expect(button).toBeDisabled()
  await input.press('Enter')
  await expect(button).toBeDisabled()
  await expect(page).toHaveURL('/search')
  expect(calls).toBe(0)
  await page.goto('/search?q=%20%20')
  await expect(
    page.getByText('검색어를 입력하거나 필터를 선택해 주세요.', {
      exact: true,
    }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('searchbox').fill('노래')
  await expect(button).toBeEnabled()
  await button.click()
  await expect(
    page.getByText('찾은 노래가 없습니다', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(1)
  await page.goto('/')
  await page.getByRole('searchbox').fill('   ')
  await page
    .getByRole('search', { name: '노래 검색', exact: true })
    .getByRole('button', { name: '검색', exact: true })
    .click()
  await expect(page).toHaveURL('/')
})

test('기간 변경은 이전 커서를 폐기하고 뒤로 가기·한쪽 기간·초기화를 복원한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  const requests: URLSearchParams[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    const params = new URL(route.request().url()).searchParams
    requests.push(params)
    const changed =
      params.get('startDate') === '2025-06-01' || !params.has('endDate')
    return route.fulfill({
      json: changed
        ? {
            ...emptyPage,
            items: [makeSong(30, { title: '변경한 기간의 곡' })],
            totalCount: 1,
          }
        : params.has('cursor')
          ? { ...emptyPage, items: [makeSong(21)], totalCount: 21 }
          : {
              items: Array.from({ length: 20 }, (_, i) => makeSong(i + 1)),
              hasNext: true,
              nextCursor: 'period-next+/=',
              totalCount: 21,
            },
    })
  })
  await page.goto('/search?q=노래&startDate=2025-01-01&endDate=2025-12-31')
  await expect(page.getByRole('article')).toHaveCount(20)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(21)
  await page.getByLabel('시작일', { exact: true }).fill('2025-06-01')
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(
    page.getByText('변경한 기간의 곡', { exact: true }),
  ).toBeVisible()
  expect(requests.at(-1)?.get('cursor')).toBeNull()
  expect(requests.at(-1)?.get('endDate')).toBe('2025-12-31')
  await expect(page.getByRole('article')).toHaveCount(1)
  await page.goBack()
  await expect(page.getByLabel('시작일', { exact: true })).toHaveValue(
    '2025-01-01',
  )
  await expect(page.getByRole('article')).toHaveCount(21)
  await page.getByLabel('종료일', { exact: true }).fill('')
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(
    page.getByText('변경한 기간의 곡', { exact: true }),
  ).toBeVisible()
  expect(requests.at(-1)?.has('endDate')).toBe(false)
  expect(requests.at(-1)?.has('cursor')).toBe(false)
  await page.reload()
  await expect(page.getByLabel('시작일', { exact: true })).toHaveValue(
    '2025-01-01',
  )
  await expect(page.getByLabel('종료일', { exact: true })).toHaveValue('')
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(page).toHaveURL('/search')
  await expect(page.getByLabel('시작일', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('종료일', { exact: true })).toHaveValue('')
})

test('역전된 기간은 양쪽 입력 오류로 표시하고 요청하지 않는다', async ({
  page,
}) => {
  let calls = 0
  await page.route('**/api/v1/songs?*', (route) => {
    calls++
    return route.fulfill({ json: emptyPage })
  })
  await page.goto('/search')
  await page.getByRole('searchbox').fill('노래')
  await page.getByLabel('시작일', { exact: true }).fill('2025-10-01')
  await page.getByLabel('종료일', { exact: true }).fill('2025-09-30')
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(page.getByLabel('시작일', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.getByLabel('종료일', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.getByLabel('시작일', { exact: true })).toBeFocused()
  expect(calls).toBe(0)
  await page.getByLabel('종료일', { exact: true }).fill('2025-10-01')
  await page.getByRole('button', { name: '검색 결과 보기' }).click()
  await expect(
    page.getByText('찾은 노래가 없습니다', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(1)
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

test('검색어 없이 멤버만 선택해도 검색하고 초기화하면 다시 차단한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    requests.push(new URL(route.request().url()).searchParams)
    return route.fulfill({ json: emptyPage })
  })
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({
      json: {
        items: [
          { id: memberA, name: '아라하시 타비', activityStatus: 'ACTIVE' },
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  await page.goto('/search')
  const button = page.getByRole('button', {
    name: '검색 결과 보기',
    exact: true,
  })
  await expect(button).toBeDisabled()
  await page
    .getByRole('button', { name: '멤버 전체 멤버', exact: true })
    .click()
  const dialog = page.getByRole('dialog', { name: '멤버 선택' })
  await dialog
    .getByRole('checkbox', { name: '아라하시 타비', exact: true })
    .check()
  await dialog.getByRole('button', { name: '선택 완료' }).click()
  await expect(button).toBeEnabled()
  expect(requests).toHaveLength(0)
  await button.click()
  await expect(
    page.getByText('찾은 노래가 없습니다', { exact: true }),
  ).toBeVisible()
  expect(requests[0]?.get('mode')).toBe('BROWSE')
  expect(requests[0]?.has('q')).toBe(false)
  expect(requests[0]?.getAll('memberIds')).toEqual([memberA])
  expect(requests[0]?.get('sort')).toBe('LATEST')
  await page.reload()
  await expect(button).toBeEnabled()
  await expect(page.getByRole('searchbox')).toHaveValue('')
  await expect(
    page.getByText('찾은 노래가 없습니다', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(page).toHaveURL('/search')
  await expect(button).toBeDisabled()
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
    page.getByRole('heading', { name: '검색 결과 2곡', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(cursors).toEqual([null, 'old-next', 'old-third', null])
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(2)
  expect(cursors).toEqual([null, 'old-next', 'old-third', null, 'new-next'])
})
