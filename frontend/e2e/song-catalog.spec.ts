import { test, expect, makeSong, emptyPage } from './fixtures'

test('전체곡은 작은 카드와 통합 검색 헤더를 표시한다', async ({ page }) => {
  const songs = [makeSong(1), makeSong(2, { type: 'ORIGINAL' }), makeSong(3)]
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: songs, totalCount: 3 } }),
  )
  await page.goto('/songs')
  await expect(
    page.getByRole('heading', { name: '전체곡', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(3)
  await expect(page.getByRole('article').nth(0)).toContainText('커버')
  await expect(page.getByRole('article').nth(1)).toContainText('오리지널')
  await expect(
    page.getByRole('region', { name: '전체곡 목록', exact: true }),
  ).toHaveCSS('transform', 'none')
  const first = await page.getByRole('article').nth(0).boundingBox()
  const second = await page.getByRole('article').nth(1).boundingBox()
  const third = await page.getByRole('article').nth(2).boundingBox()
  expect(first?.y).toBe(second?.y)
  if ((page.viewportSize()?.width ?? 0) >= 768) {
    const columns = page.viewportSize()!.width >= 1024 ? 6 : 3
    const expectedWidth =
      (page.viewportSize()!.width - 64 - (columns - 1) * 24) / columns
    expect(Math.abs(first!.width - expectedWidth)).toBeLessThan(1)
    expect(third?.y).toBe(first?.y)
  } else expect(third!.y).toBeGreaterThan(first!.y)
  await expect(
    page.getByRole('article').first().getByRole('link'),
  ).toHaveAttribute('href', `/songs/${songs[0]!.id}`)
  await page.getByRole('searchbox').fill('곡 검색')
  await page.getByRole('searchbox').press('Enter')
  await expect(page).toHaveURL(/\/search\?q=/)
  await expect(page.getByRole('searchbox')).toHaveValue('곡 검색')
  await page.goBack()
  await expect(
    page.getByRole('heading', { name: '전체곡', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('searchbox')).toHaveValue('')
})

test('전체곡의 긴 제목과 멤버명은 메인처럼 이동하며 모션 줄이기 설정을 따른다', async ({
  page,
}) => {
  const title = '아주 긴 노래 제목 '.repeat(12)
  const name = '아주 긴 참여 멤버 이름 '.repeat(10)
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({
      json: {
        ...emptyPage,
        items: [
          makeSong(1, {
            title,
            participants: [{ ...makeSong(1).participants[0]!, name }],
          }),
        ],
        totalCount: 1,
      },
    }),
  )
  await page.goto('/songs')
  await expect(page.getByRole('article')).toHaveCount(1)
  const card = page.getByRole('article')
  await expect
    .poll(() =>
      card.evaluate(
        (el) =>
          el
            .getAnimations({ subtree: true })
            .filter(
              (a) =>
                a.playState === 'running' &&
                a.effect?.getTiming().iterations === Infinity,
            ).length,
      ),
    )
    .toBe(2)
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '다크 모드로 변경' }).click()
  await page.keyboard.press('Escape')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect
    .poll(() =>
      card.evaluate((el) => el.getAnimations({ subtree: true }).length),
    )
    .toBe(0)
})

test('분류 미선택과 세 정렬을 조합하고 URL·새로고침을 유지한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    requests.push(new URL(route.request().url()).searchParams)
    return route.fulfill({
      json: { ...emptyPage, items: [makeSong(1)], totalCount: 1 },
    })
  })
  await page.goto('/songs')
  for (const [label, sort] of [
    ['오래된순', 'OLDEST'],
    ['인기순', 'VIEWS'],
    ['최신순', 'LATEST'],
  ]) {
    await page.getByRole('button', { name: /정렬 선택:/ }).click()
    await page.getByRole('menuitemradio', { name: label, exact: true }).click()
    await expect.poll(() => requests.at(-1)?.get('sort')).toBe(sort)
    expect(requests.at(-1)?.has('cursor')).toBe(false)
    await expect(page.getByRole('menu')).toHaveCount(0)
  }
  await page.getByRole('button', { name: /정렬 선택:/ }).click()
  await page.keyboard.press('Escape')
  await expect(
    page.getByRole('button', { name: '정렬 선택: 최신순' }),
  ).toBeFocused()
  await page.reload()
  await expect(
    page.getByRole('button', { name: '오리지널 / 커버', exact: true }),
  ).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByText('모든 노래를 불러왔습니다.')).toHaveCount(0)
})

test('분류는 하나만 선택하고 같은 분류를 다시 누르면 해제하며 정렬을 유지한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  await page.route('**/api/v1/songs/groups?*', (route) => {
    requests.push(new URL(route.request().url()).searchParams)
    return route.fulfill({
      json: { items: [], hasNext: false, nextCursor: null },
    })
  })
  await page.goto('/songs?sort=OLDEST')
  for (const [label, groupBy] of [
    ['오리지널 / 커버', 'TYPE'],
    ['멤버별', 'MEMBER'],
    ['원곡 가수별', 'ORIGINAL_ARTIST'],
  ]) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect.poll(() => requests.at(-1)?.get('groupBy')).toBe(groupBy)
    expect(requests.at(-1)?.get('sort')).toBe('OLDEST')
    expect(requests.at(-1)?.has('cursor')).toBe(false)
  }
  await page.getByRole('button', { name: /정렬 선택:/ }).click()
  await page.getByRole('menuitemradio', { name: '인기순', exact: true }).click()
  await expect.poll(() => requests.at(-1)?.get('sort')).toBe('VIEWS')
  expect(requests.at(-1)?.get('groupBy')).toBe('ORIGINAL_ARTIST')
  expect(requests.at(-1)?.has('cursor')).toBe(false)
  await page.reload()
  await expect(
    page.getByRole('button', { name: '원곡 가수별', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '원곡 가수별', exact: true }).click()
  await expect(page).toHaveURL('/songs?sort=VIEWS')
  await expect(
    page.getByRole('button', { name: '원곡 가수별', exact: true }),
  ).toHaveAttribute('aria-pressed', 'false')
})

const groupPage = (name: string, id: string, index: number) => ({
  id,
  name,
  songs: {
    items: Array.from({ length: 6 }, (_, i) => makeSong(index + i)),
    hasNext: true,
    nextCursor: `${id}-next`,
    totalCount: 12,
  },
})

test('다음 화면을 미리 받는 중에도 받은 곡은 넘길 수 있고 빠른 스크롤은 같은 요청을 기다린다', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  let release = () => {}
  const pending = new Promise<void>((resolve) => {
    release = resolve
  })
  let calls = 0
  await page.route('**/api/v1/songs/groups?*', (route) => {
    expect(new URL(route.request().url()).searchParams.get('songSize')).toBe(
      '18',
    )
    return route.fulfill({
      json: {
        items: [
          {
            id: 'COVER',
            name: '커버',
            songs: {
              items: Array.from({ length: 18 }, (_, i) => makeSong(i + 1)),
              hasNext: true,
              nextCursor: 'more',
              totalCount: 36,
            },
          },
        ],
        hasNext: false,
        nextCursor: null,
      },
    })
  })
  await page.route('**/api/v1/songs/groups/COVER/songs?*', async (route) => {
    calls++
    await pending
    await route.fulfill({
      json: {
        ...emptyPage,
        items: Array.from({ length: 18 }, (_, i) => makeSong(i + 19)),
        totalCount: 36,
      },
    })
  })
  try {
    await page.goto('/songs?groupBy=TYPE')
    const section = page.getByRole('region', {
      name: '커버 곡 목록',
      exact: true,
    })
    const scroller = section.getByRole('group', {
      name: '커버 가로 스크롤',
      exact: true,
    })
    const size = page.viewportSize()!.width >= 1024 ? 6 : 2
    const start = size === 6 ? 1 : 6
    const width = await scroller.evaluate((el) => el.clientWidth)
    await scroller.hover()
    await page.mouse.wheel(width * start, 0)
    await expect.poll(() => calls).toBe(1)
    const next = section.getByRole('button', {
      name: '커버 다음 곡 보기',
      exact: true,
    })
    await expect(next).toBeEnabled()
    await next.click()
    await expect(
      section.getByRole('heading', {
        name: `테스트 노래 ${(start + 1) * size + 1}`,
        exact: true,
      }),
    ).toBeVisible()
    await scroller.hover()
    await page.mouse.wheel(width * (18 / size - start - 1), 0)
    release()
    await expect(
      section.getByRole('heading', { name: '테스트 노래 19', exact: true }),
    ).toBeVisible()
    expect(calls).toBe(1)
  } finally {
    release()
  }
})

test('각 섹션은 가로 스크롤로 추가 곡을 불러오고 화살표 이동과 위치를 공유한다', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/v1/songs/groups?*', (route) =>
    route.fulfill({
      json: {
        items: [
          groupPage('커버', 'COVER', 1),
          {
            id: 'ORIGINAL',
            name: '오리지널',
            songs: { ...emptyPage, items: [makeSong(30)], totalCount: 1 },
          },
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  let calls = 0
  await page.route('**/api/v1/songs/groups/COVER/songs?*', (route) => {
    calls++
    expect(new URL(route.request().url()).searchParams.get('cursor')).toBe(
      'COVER-next',
    )
    return route.fulfill({
      json: { ...emptyPage, items: [makeSong(7), makeSong(8)], totalCount: 8 },
    })
  })
  await page.goto('/songs?groupBy=TYPE')
  const section = page.getByRole('region', {
    name: '커버 곡 목록',
    exact: true,
  })
  const scroller = section.getByRole('group', {
    name: '커버 가로 스크롤',
    exact: true,
  })
  const pageSize = page.viewportSize()!.width >= 1024 ? 6 : 2
  for (let index = 1; index < Math.ceil(8 / pageSize); index++) {
    await scroller.hover()
    const width = await scroller.evaluate((el) => el.clientWidth)
    await page.mouse.wheel(width, 0)
    await expect(
      section.getByRole('heading', {
        name: `테스트 노래 ${index * pageSize + 1}`,
        exact: true,
      }),
    ).toBeVisible()
  }
  expect(calls).toBe(1)
  await expect(
    section.getByRole('button', { name: '커버 다음 곡 보기', exact: true }),
  ).toHaveCount(0)
  await section
    .getByRole('button', { name: '커버 이전 곡 보기', exact: true })
    .click()
  await expect(
    section.getByRole('heading', {
      name: `테스트 노래 ${(Math.ceil(8 / pageSize) - 2) * pageSize + 1}`,
      exact: true,
    }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('region', { name: '오리지널 곡 목록', exact: true })
      .getByRole('article'),
  ).toHaveCount(1)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('섹션을 펼치면 전체 곡을 조회하고 오류 후 재개하며 접은 뒤 다음 버튼과 같은 조건으로 숨긴다', async ({
  page,
}) => {
  await page.route('**/api/v1/songs/groups?*', (route) =>
    route.fulfill({
      json: {
        items: [
          groupPage('커버', 'COVER', 1),
          {
            id: 'ORIGINAL',
            name: '오리지널',
            songs: {
              ...emptyPage,
              items: [makeSong(30, { type: 'ORIGINAL' })],
              totalCount: 1,
            },
          },
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  const cursors: (string | null)[] = []
  let fail = true
  await page.route('**/api/v1/songs/groups/*/songs?*', (route) => {
    const url = new URL(route.request().url())
    expect(url.pathname).toBe('/api/v1/songs/groups/COVER/songs')
    const cursor = url.searchParams.get('cursor')
    cursors.push(cursor)
    if (fail)
      return route.fulfill({ status: 400, json: { code: 'TEMPORARY_FAILURE' } })
    const first = cursor === 'COVER-next'
    return route.fulfill({
      json: {
        items: Array.from({ length: first ? 3 : 2 }, (_, i) =>
          makeSong((first ? 7 : 10) + i),
        ),
        hasNext: first,
        nextCursor: first ? 'last' : null,
        totalCount: 11,
      },
    })
  })
  await page.goto('/songs?groupBy=TYPE')
  const section = page.getByRole('region', {
    name: '커버 곡 목록',
    exact: true,
  })
  await expect(
    page.getByRole('button', { name: '오리지널 곡 모두 펼치기', exact: true }),
  ).toHaveCount(0)
  const expand = section.getByRole('button', {
    name: '커버 곡 모두 펼치기',
    exact: true,
  })
  await expand.click()
  await expect(
    section.getByText('이 섹션의 곡을 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(section.getByRole('article')).toHaveCount(6)
  await expect(
    section.getByRole('button', { name: '커버 곡 접기', exact: true }),
  ).toHaveAttribute('aria-expanded', 'true')
  fail = false
  await section.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(section.getByRole('article')).toHaveCount(11)
  expect(cursors).toEqual(['COVER-next', 'COVER-next', 'last'])
  await expect(
    section.getByRole('button', { name: '커버 다음 곡 보기', exact: true }),
  ).toHaveCount(0)
  await section
    .getByRole('button', { name: '커버 곡 접기', exact: true })
    .click()
  const pageSize = page.viewportSize()!.width >= 1024 ? 6 : 2
  await expect(section.getByRole('article')).toHaveCount(pageSize)
  for (let index = 1; index < Math.ceil(11 / pageSize); index++) {
    await section
      .getByRole('button', { name: '커버 다음 곡 보기', exact: true })
      .click()
    await expect(
      section.getByRole('heading', {
        name: `테스트 노래 ${index * pageSize + 1}`,
        exact: true,
      }),
    ).toBeVisible()
  }
  await expect(expand).toHaveCount(0)
  await section
    .getByRole('button', { name: '커버 이전 곡 보기', exact: true })
    .click()
  await expect(expand).toBeVisible()
  expect(cursors).toHaveLength(3)
})

test('원곡 가수별은 오리지널 그룹을 먼저 표시하고 정렬별 독립 커서와 빈 그룹 생략을 유지한다', async ({
  page,
}) => {
  let includeOriginals = true
  const songRequests: string[] = []
  await page.route('**/api/v1/songs/groups?*', (route) => {
    const sort = new URL(route.request().url()).searchParams.get('sort')
    const original = groupPage('오리지널', 'ORIGINAL', 1)
    original.songs.items = original.songs.items.map((song) => ({
      ...song,
      type: 'ORIGINAL',
    }))
    original.songs.nextCursor = `original-${sort}`
    original.songs.totalCount = 8
    return route.fulfill({
      json: {
        items: [
          ...(includeOriginals ? [original] : []),
          {
            id: makeSong(501).id,
            name: '가수 A',
            songs: {
              ...emptyPage,
              items: [makeSong(20), makeSong(21)],
              totalCount: 2,
            },
          },
          {
            id: 'OTHERS',
            name: '기타',
            songs: { ...emptyPage, items: [makeSong(30)], totalCount: 1 },
          },
        ],
        hasNext: false,
        nextCursor: null,
      },
    })
  })
  await page.route('**/api/v1/songs/groups/*/songs?*', (route) => {
    const url = new URL(route.request().url())
    expect(url.pathname).toBe('/api/v1/songs/groups/ORIGINAL/songs')
    expect(url.searchParams.get('groupBy')).toBe('ORIGINAL_ARTIST')
    const sort = url.searchParams.get('sort')!
    expect(url.searchParams.get('cursor')).toBe(`original-${sort}`)
    songRequests.push(sort)
    return route.fulfill({
      json: {
        ...emptyPage,
        items: [7, 8].map((index) => makeSong(index, { type: 'ORIGINAL' })),
        totalCount: 8,
      },
    })
  })
  await page.goto('/songs?groupBy=ORIGINAL_ARTIST')
  for (const [label, sort] of [
    ['최신순', 'LATEST'],
    ['오래된순', 'OLDEST'],
    ['인기순', 'VIEWS'],
  ]) {
    if (sort !== 'LATEST') {
      await page.getByRole('button', { name: /정렬 선택:/ }).click()
      await page
        .getByRole('menuitemradio', { name: label, exact: true })
        .click()
    }
    await expect(page.getByRole('heading', { level: 2 })).toHaveText([
      '스텔라이브 오리지널',
      '가수 A',
      '기타',
    ])
    const original = page.getByRole('region', {
      name: '스텔라이브 오리지널 곡 목록',
      exact: true,
    })
    const pageSize = page.viewportSize()!.width >= 1024 ? 6 : 2
    for (let i = 0; i < 6 / pageSize; i++)
      await original
        .getByRole('button', { name: '스텔라이브 오리지널 다음 곡 보기' })
        .click()
    await expect(
      original.getByRole('heading', { name: '테스트 노래 7', exact: true }),
    ).toBeVisible()
    for (const article of await original.getByRole('article').all())
      await expect(article).toContainText('오리지널')
    await expect(
      page
        .getByRole('region', { name: '가수 A 곡 목록', exact: true })
        .getByRole('article'),
    ).toHaveCount(2)
    await expect(
      page
        .getByRole('region', { name: '기타 곡 목록', exact: true })
        .getByRole('article'),
    ).toHaveCount(1)
  }
  expect(songRequests).toEqual(['LATEST', 'OLDEST', 'VIEWS'])
  includeOriginals = false
  await page.reload()
  await expect(page.getByRole('heading', { level: 2 })).toHaveText([
    '가수 A',
    '기타',
  ])
  await expect(
    page.getByRole('region', {
      name: '스텔라이브 오리지널 곡 목록',
      exact: true,
    }),
  ).toHaveCount(0)
})

test('각 섹션은 자기 커서만 사용하며 분류 목록과 곡의 추가 조회를 분리한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  const calls: string[] = []
  await page.route('**/api/v1/songs/groups?*', (route) => {
    const p = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: p.has('cursor')
        ? {
            items: [groupPage('기타', 'OTHERS', 40)],
            hasNext: false,
            nextCursor: null,
          }
        : {
            items: [
              groupPage('가수 A', makeSong(501).id, 1),
              groupPage('가수 B', makeSong(502).id, 20),
            ],
            hasNext: true,
            nextCursor: 'group-next',
          },
    })
  })
  await page.route('**/api/v1/songs/groups/*/songs?*', (route) => {
    const url = new URL(route.request().url())
    calls.push(url.pathname + '?' + url.searchParams.get('cursor'))
    expect(url.searchParams.get('groupBy')).toBe('ORIGINAL_ARTIST')
    expect(url.searchParams.get('cursor')).toBe(`${makeSong(502).id}-next`)
    return route.fulfill({
      json: {
        ...emptyPage,
        items: [makeSong(27), makeSong(28)],
        totalCount: 8,
      },
    })
  })
  await page.goto('/songs?groupBy=ORIGINAL_ARTIST')
  const cover = page.getByRole('region', {
    name: '가수 B 곡 목록',
    exact: true,
  })
  await expect(cover).toBeVisible()
  for (let i = 0; i < 3 && calls.length === 0; i++)
    await cover.getByRole('button', { name: '가수 B 다음 곡 보기' }).click()
  await expect.poll(() => calls.length).toBe(1)
  expect(calls[0]).toContain(
    `/${makeSong(502).id}/songs?${makeSong(502).id}-next`,
  )
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByRole('region', { name: '기타 곡 목록', exact: true }),
  ).toBeVisible()
  expect(calls).toHaveLength(1)
})

test('추가 조회 경계에서도 한 번 클릭으로 다음 줄을 채워 이동하고 마지막에만 남은 곡을 표시한다', async ({
  page,
}, info) => {
  for (const width of info.project.name === 'desktop' ? [768, 1440] : [390]) {
    await page.setViewportSize({ width, height: 900 })
    const cursors: (string | null)[] = []
    await page.route('**/api/v1/songs/groups?*', (route) =>
      route.fulfill({
        json: {
          items: [groupPage('커버', 'COVER', 1)],
          hasNext: false,
          nextCursor: null,
        },
      }),
    )
    await page.route('**/api/v1/songs/groups/COVER/songs?*', (route) => {
      const cursor = new URL(route.request().url()).searchParams.get('cursor')
      cursors.push(cursor)
      const first = cursor === 'COVER-next'
      return route.fulfill({
        json: {
          items: Array.from({ length: first ? 20 : 14 }, (_, i) =>
            makeSong((first ? 7 : 27) + i),
          ),
          hasNext: first,
          nextCursor: first ? 'COVER-last' : null,
          totalCount: 40,
        },
      })
    })
    await page.goto('/songs?groupBy=TYPE')
    const section = page.getByRole('region', {
      name: '커버 곡 목록',
      exact: true,
    })
    const pageSize = width >= 1024 ? 6 : width >= 768 ? 3 : 2
    const next = section.getByRole('button', { name: '커버 다음 곡 보기' })
    for (let index = 1; index < Math.ceil(40 / pageSize); index++) {
      await next.click()
      await expect(
        section.getByRole('heading', {
          name: `테스트 노래 ${index * pageSize + 1}`,
          exact: true,
        }),
      ).toBeVisible()
      await expect(section.getByRole('article')).toHaveCount(
        Math.min(pageSize, 40 - index * pageSize),
      )
    }
    await expect(next).toHaveCount(0)
    expect(cursors).toEqual(['COVER-next', 'COVER-last'])
    await section.getByRole('button', { name: '커버 이전 곡 보기' }).click()
    await expect(section.getByRole('article')).toHaveCount(pageSize)
    expect(cursors).toHaveLength(2)
    await page.unroute('**/api/v1/songs/groups?*')
    await page.unroute('**/api/v1/songs/groups/COVER/songs?*')
  }
})

test('인기순 섹션 커서가 만료되면 다른 섹션을 유지하고 해당 섹션 첫 페이지로 재조회한다', async ({
  page,
}) => {
  const cursors: (string | null)[] = []
  await page.route('**/api/v1/songs/groups?*', (route) =>
    route.fulfill({
      json: {
        items: [
          groupPage('오리지널', 'ORIGINAL', 1),
          groupPage('커버', 'COVER', 20),
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  await page.route('**/api/v1/songs/groups/COVER/songs?*', (route) => {
    const p = new URL(route.request().url()).searchParams
    cursors.push(p.get('cursor'))
    return route.fulfill(
      p.has('cursor')
        ? { status: 409, json: { code: 'VIEW_PUBLICATION_CHANGED' } }
        : {
            json: {
              ...emptyPage,
              items: [makeSong(90, { title: '갱신된 커버' })],
              totalCount: 1,
            },
          },
    )
  })
  await page.goto('/songs?groupBy=TYPE&sort=VIEWS')
  const cover = page.getByRole('region', { name: '커버 곡 목록', exact: true })
  for (let i = 0; i < 3 && cursors.length === 0; i++)
    await cover.getByRole('button', { name: '커버 다음 곡 보기' }).click()
  await expect(
    cover.getByText('이 섹션의 곡을 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('region', { name: '오리지널 곡 목록', exact: true }),
  ).toBeVisible()
  await cover.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    cover.getByRole('heading', { name: '갱신된 커버', exact: true }),
  ).toBeVisible()
  expect(cursors).toEqual(['COVER-next', null])
})

test('분류 목록의 인기순 커서 만료는 그룹 목록도 첫 페이지부터 재조회한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  const cursors: (string | null)[] = []
  let refreshed = false
  await page.route('**/api/v1/songs/groups?*', (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    cursors.push(cursor)
    if (cursor) {
      refreshed = true
      return route.fulfill({
        status: 409,
        json: { code: 'VIEW_PUBLICATION_CHANGED' },
      })
    }
    return route.fulfill({
      json: {
        items: [
          {
            id: makeSong(510).id,
            name: refreshed ? '갱신된 멤버' : '이전 멤버',
            songs: {
              ...emptyPage,
              items: [makeSong(refreshed ? 100 : 1)],
              totalCount: 1,
            },
          },
        ],
        hasNext: !refreshed,
        nextCursor: refreshed ? null : 'groups-next',
      },
    })
  })
  await page.goto('/songs?groupBy=MEMBER&sort=VIEWS')
  await expect(
    page.getByRole('heading', { name: '이전 멤버', exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByText('분류 목록을 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '갱신된 멤버', exact: true }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('article')
      .getByRole('heading', { name: '테스트 노래 100', exact: true }),
  ).toBeVisible()
  expect(cursors).toEqual([null, 'groups-next', null])
})

test('분류 목록 조회 실패를 빈 목록으로 표시하지 않고 재시도한다', async ({
  page,
}) => {
  let fail = true
  await page.route('**/api/v1/songs/groups?*', (route) =>
    route.fulfill(
      fail
        ? { status: 400, json: { code: 'INVALID_CATALOG_QUERY' } }
        : { json: { items: [], hasNext: false, nextCursor: null } },
    ),
  )
  await page.goto('/songs?groupBy=TYPE')
  await expect(
    page.getByText('분류 목록을 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('아직 공개된 노래가 없습니다', { exact: true }),
  ).toHaveCount(0)
  fail = false
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByText('아직 공개된 노래가 없습니다', { exact: true }),
  ).toBeVisible()
})

test('분류 선택 여부와 화면 폭에 관계없이 카드 크기가 일치한다', async ({
  page,
}, info) => {
  const songs = Array.from({ length: 6 }, (_, i) => makeSong(i + 1))
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: songs, totalCount: 6 } }),
  )
  await page.route('**/api/v1/songs/groups?*', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'ORIGINAL',
            name: '오리지널',
            songs: {
              ...emptyPage,
              items: songs,
              totalCount: 12,
              hasNext: true,
              nextCursor: 'more',
            },
          },
        ],
        hasNext: false,
        nextCursor: null,
      },
    }),
  )
  for (const width of info.project.name === 'desktop'
    ? [767, 768, 881, 1024, 1440]
    : [390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/songs')
    const flat = await page.getByRole('article').first().boundingBox()
    await page
      .getByRole('button', { name: '오리지널 / 커버', exact: true })
      .click()
    const region = page.getByRole('region', {
      name: '오리지널 곡 목록',
      exact: true,
    })
    await expect(region).toBeVisible()
    await expect
      .poll(() =>
        region.evaluate((el) => {
          const image = el
            .querySelector('article .aspect-video')!
            .getBoundingClientRect()
          const arrow = el
            .querySelector('button[aria-label="오리지널 다음 곡 보기"]')!
            .getBoundingClientRect()
          return Math.abs(
            image.top + image.height / 2 - (arrow.top + arrow.height / 2),
          )
        }),
      )
      .toBeLessThan(1)
    const expected = width >= 1024 ? 6 : width >= 768 ? 3 : 2
    await expect(region.getByRole('article')).toHaveCount(expected)
    for (const card of await region.getByRole('article').all()) {
      const bounds = await card.boundingBox()
      expect(Math.abs(bounds!.width - flat!.width)).toBeLessThan(1)
    }
  }
})
