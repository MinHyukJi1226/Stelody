import { test, expect, emptyPage } from './fixtures'
import {
  member,
  graduate,
  memberDetail,
  memberSong,
  memberSongs,
} from './member-fixtures'

test.beforeEach(async ({ page }) => {
  await page.route(`**/api/v1/members/${member.id}`, (route) =>
    route.fulfill({ json: memberDetail }),
  )
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) =>
    route.fulfill({ json: memberSongs }),
  )
})

test('멤버 목록은 유닛별 프로필과 졸업 섹션을 표시하고 공통 검색 헤더를 사용한다', async ({
  page,
}) => {
  const rows = [
    ...['Everys', 'Universe', 'Cliché', 'ABYSS'].flatMap((unitName, index) =>
      Array.from({ length: index === 0 ? 2 : 4 }, (_, j) => ({
        ...member,
        id: `00000000-0000-4000-8000-${String(200 + index * 10 + j).padStart(12, '0')}`,
        name: `${unitName} 멤버 ${j + 1}`,
        unitName,
      })),
    ),
    graduate,
  ]
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({ json: { items: rows, hasNext: false, nextCursor: null } }),
  )
  await page.goto('/members')
  await expect(page.getByRole('heading', { level: 2 })).toHaveText([
    'Everys',
    'Universe',
    'Cliché',
    'ABYSS',
    'GRADUATE',
  ])
  for (const [unit, count] of [
    ['Everys', 2],
    ['Universe', 4],
    ['Cliché', 4],
    ['ABYSS', 4],
    ['GRADUATE', 1],
  ] as const) {
    const section = page.getByRole('region', { name: unit, exact: true })
    await expect(section.getByRole('link')).toHaveCount(count)
    await expect(section.locator('span[aria-hidden=true]').first()).toHaveCSS(
      'width',
      '72px',
    )
    await expect(section.locator('span[aria-hidden=true]').first()).toHaveCSS(
      'height',
      '72px',
    )
  }
  await expect(
    page
      .getByRole('region', { name: 'GRADUATE', exact: true })
      .getByRole('link'),
  ).toHaveAttribute('href', `/members/${graduate.id}`)
  await expect(page.locator('main footer')).toContainText(
    '공식 서비스가 아닙니다.',
  )
  await page.getByRole('searchbox').fill('유니')
  await page.getByRole('searchbox').press('Enter')
  await expect(page).toHaveURL(/\/search\?q=/)
  await page.goBack()
  await expect(page.getByRole('searchbox')).toHaveValue('')
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page
    .getByRole('button', { name: '다크 모드로 변경', exact: true })
    .click()
  await page.keyboard.press('Escape')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('멤버 목록은 서버 순서·졸업 표시를 유지하고 추가 페이지 중복을 제거한다', async ({
  page,
}) => {
  const cursors: (string | null)[] = []
  await page.route('**/api/v1/members?*', (route) => {
    const p = new URL(route.request().url()).searchParams
    expect(p.get('status')).toBe('ALL')
    cursors.push(p.get('cursor'))
    return route.fulfill({
      json: p.has('cursor')
        ? { items: [member, graduate], hasNext: false, nextCursor: null }
        : { items: [member], hasNext: true, nextCursor: 'members+/=&next' },
    })
  })
  await page.goto('/members')
  await expect(page.getByRole('heading', { name: '전체 멤버' })).toBeVisible()
  await page.getByRole('button', { name: '멤버 더 보기' }).click()
  const roster = page.getByLabel('모든 멤버', { exact: true })
  await expect(roster.getByRole('link')).toHaveCount(2)
  await expect(roster.getByRole('link').nth(0)).toHaveAccessibleName(
    member.name,
  )
  await expect(roster.getByRole('link').nth(1)).toHaveAccessibleName(
    `${graduate.name} (졸업)`,
  )
  expect(cursors).toEqual([null, 'members+/=&next'])
  await roster.getByRole('link', { name: member.name, exact: true }).click()
  await expect(
    page.getByRole('heading', { name: member.name, exact: true }),
  ).toBeVisible()
  await expect(page.getByText('Universe', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('link', { name: '공식 YouTube', exact: true }),
  ).toHaveAttribute('href', memberDetail.channels[0]!.url)
  await expect(
    page.getByRole('link', { name: '치지직 채널', exact: true }),
  ).toHaveAttribute('rel', 'noopener noreferrer')
  await page.goBack()
  await expect(roster.getByRole('link')).toHaveCount(2)
  expect(cursors).toHaveLength(2)
})

test('멤버 목록의 로딩·오류·재시도·빈 목록을 구분한다', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let fail = true
  await page.route('**/api/v1/members?*', async (route) => {
    await gate
    await route.fulfill(
      fail
        ? { status: 400, json: { code: 'INVALID_CATALOG_QUERY' } }
        : { json: emptyPage },
    )
  })
  try {
    await page.goto('/members')
    await expect(
      page.getByRole('status', { name: '멤버 불러오는 중' }),
    ).toBeVisible()
    release()
    await expect(
      page.getByText('멤버를 불러오지 못했습니다', { exact: true }),
    ).toBeVisible()
    fail = false
    await page.getByRole('button', { name: '다시 시도', exact: true }).click()
    await expect(
      page.getByText('등록된 멤버가 없습니다', { exact: true }),
    ).toBeVisible()
  } finally {
    release()
  }
})

test('개인곡은 새 필터와 독립 커서로 조회하고 다인 전환·해제·뒤로 가기를 지원한다', async ({
  page,
}) => {
  const covers: URLSearchParams[] = []
  let otherCalls = 0
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    expect(params.has('multipleParticipants')).toBe(false)
    expect(params.has('collaboration')).toBe(false)
    if (params.get('type') !== 'COVER') {
      otherCalls++
      return route.fulfill({ json: memberSongs })
    }
    covers.push(params)
    const solo = params.get('participation') === 'SOLO'
    return route.fulfill({
      json: solo
        ? params.has('cursor')
          ? { ...emptyPage, items: [memberSong(17)], totalCount: 7 }
          : {
              items: Array.from({ length: 6 }, (_, i) => memberSong(i + 11)),
              totalCount: 7,
              hasNext: true,
              nextCursor: 'solo-next',
            }
        : memberSongs,
    })
  })
  await page.goto(`/members/${member.id}`)
  await page.getByRole('button', { name: '개인', exact: true }).click()
  const solo = page.getByRole('region', {
    name: '커버 개인 곡 목록',
    exact: true,
  })
  await expect(solo.getByRole('heading', { level: 2 })).toHaveText(
    '커버 개인 7곡',
  )
  await expect(
    page.getByRole('button', { name: '개인', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await solo
    .getByRole('button', { name: '커버 개인 곡 모두 펼치기', exact: true })
    .click()
  await expect(solo.getByRole('article')).toHaveCount(7)
  await page.getByRole('button', { name: '듀엣 및 단체', exact: true }).click()
  await expect(
    page.getByRole('region', {
      name: '커버 듀엣 및 단체 곡 목록',
      exact: true,
    }),
  ).toBeVisible()
  expect(covers.at(-1)!.has('cursor')).toBe(false)
  await page.goBack()
  await expect(solo).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('button', { name: '개인', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '개인', exact: true }).click()
  await expect(
    page.getByRole('region', { name: '커버 전체 곡 목록', exact: true }),
  ).toBeVisible()
  expect(
    covers.slice(0, 4).map((p) => [p.get('participation'), p.get('cursor')]),
  ).toEqual([
    ['ALL', null],
    ['SOLO', null],
    ['SOLO', 'solo-next'],
    ['MULTIPLE', null],
  ])
  expect(otherCalls).toBe(4)
})

test('세 섹션이 독립 조회하고 커버 다인 필터만 URL·새로고침·뒤로 가기로 복원한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    requests.push(params)
    return route.fulfill({ json: memberSongs })
  })
  await page.goto(`/members/${member.id}`)
  for (const label of ['전체', '오리지널', '커버 전체']) {
    const section = page.getByRole('region', {
      name: `${label} 곡 목록`,
      exact: true,
    })
    await expect(section.getByRole('heading', { level: 2 })).toHaveText(
      `${label} 2곡`,
    )
    await expect(section.getByRole('article')).toHaveCount(2)
  }
  expect(requests.map((p) => p.get('type')).sort()).toEqual([
    'ALL',
    'COVER',
    'ORIGINAL',
  ])
  await expect(
    page.getByRole('button', { name: '개인', exact: true }),
  ).toBeEnabled()
  await page.getByRole('button', { name: '듀엣 및 단체', exact: true }).click()
  const cover = page.getByRole('region', {
    name: '커버 듀엣 및 단체 곡 목록',
    exact: true,
  })
  await expect(cover.getByRole('article')).toHaveCount(2)
  expect(requests.at(-1)!.get('type')).toBe('COVER')
  expect(requests.at(-1)!.get('participation')).toBe('MULTIPLE')
  expect(requests.at(-1)!.has('cursor')).toBe(false)
  await page.getByRole('button', { name: '듀엣 및 단체', exact: true }).click()
  await expect(
    page.getByRole('region', { name: '커버 전체 곡 목록', exact: true }),
  ).toBeVisible()
  await page.goBack()
  await expect(
    page.getByRole('button', { name: '듀엣 및 단체', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.reload()
  await expect(
    page.getByRole('button', { name: '듀엣 및 단체', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('한 섹션의 펼치기 실패는 다른 섹션을 유지하며 같은 커서로 재시도하고 중복을 제거한다', async ({
  page,
}) => {
  let fail = true
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    if (params.get('type') !== 'COVER')
      return route.fulfill({ json: memberSongs })
    cursors.push(params.get('cursor'))
    if (params.has('cursor') && fail)
      return route.fulfill({ status: 400, json: { code: 'TEMPORARY_FAILURE' } })
    return route.fulfill({
      json: params.has('cursor')
        ? { ...emptyPage, items: [memberSong(6), memberSong(7)], totalCount: 7 }
        : {
            items: Array.from({ length: 6 }, (_, i) => memberSong(i + 1)),
            hasNext: true,
            nextCursor: 'cover-next',
            totalCount: 7,
          },
    })
  })
  await page.goto(`/members/${member.id}`)
  const cover = page.getByRole('region', {
    name: '커버 전체 곡 목록',
    exact: true,
  })
  await cover
    .getByRole('button', { name: '커버 전체 곡 모두 펼치기', exact: true })
    .click()
  await expect(
    cover.getByText('노래를 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(cover.getByRole('article')).toHaveCount(6)
  await expect(
    page
      .getByRole('region', { name: '전체 곡 목록', exact: true })
      .getByRole('article'),
  ).toHaveCount(2)
  fail = false
  await cover.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(cover.getByRole('article')).toHaveCount(7)
  await cover
    .getByRole('button', { name: '커버 전체 곡 접기', exact: true })
    .click()
  await expect(cover.getByRole('article')).toHaveCount(
    page.viewportSize()!.width >= 1024 ? 6 : 2,
  )
  expect(cursors).toEqual([null, 'cover-next', 'cover-next'])
})

test('집계가 바뀌면 해당 섹션만 같은 조건의 첫 페이지로 다시 조회한다', async ({
  page,
}) => {
  let changed = false
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    if (params.get('type') !== 'COVER')
      return route.fulfill({ json: memberSongs })
    expect(params.get('sort')).toBe('VIEWS')
    expect(params.get('participation')).toBe('MULTIPLE')
    cursors.push(params.get('cursor'))
    if (params.has('cursor')) {
      changed = true
      return route.fulfill({
        status: 409,
        json: { code: 'VIEW_PUBLICATION_CHANGED' },
      })
    }
    return route.fulfill({
      json: changed
        ? { ...emptyPage, items: [memberSong(3)], totalCount: 1 }
        : { ...memberSongs, hasNext: true, nextCursor: 'old' },
    })
  })
  await page.goto(
    `/members/${member.id}?type=COVER&multipleParticipants=true&sort=VIEWS`,
  )
  const cover = page.getByRole('region', {
    name: '커버 듀엣 및 단체 곡 목록',
    exact: true,
  })
  await cover
    .getByRole('button', {
      name: '커버 듀엣 및 단체 곡 모두 펼치기',
      exact: true,
    })
    .click()
  await expect(
    cover.getByText('노래를 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await cover.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(cover.getByRole('article')).toHaveCount(1)
  await expect(
    cover.getByRole('heading', { name: '테스트 노래 3', exact: true }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('region', { name: '전체 곡 목록', exact: true })
      .getByRole('article'),
  ).toHaveCount(2)
  expect(cursors).toEqual([null, 'old', null])
})

test('잘못된 멤버 주소는 요청하지 않고 없는 멤버·비공개·오류를 구분한다', async ({
  page,
}) => {
  let calls = 0
  let status = 404
  await page.route(`**/api/v1/members/${member.id}`, (route) => {
    calls++
    return route.fulfill(
      status === 200
        ? { json: memberDetail }
        : {
            status,
            json: { code: status === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR' },
          },
    )
  })
  await page.goto('/members/invalid')
  await expect(
    page.getByRole('heading', { name: '멤버를 찾을 수 없습니다', exact: true }),
  ).toBeAttached()
  expect(calls).toBe(0)
  await page.goto(`/members/${member.id}`)
  await expect(
    page.getByRole('heading', {
      name: '이 화면에 접근할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  status = 403
  await page.reload()
  await expect(
    page.getByRole('heading', {
      name: '이 화면에 접근할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  status = 500
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '불러오지 못했어요', exact: true }),
  ).toBeVisible()
  status = 200
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: member.name, exact: true }),
  ).toBeVisible()
})

test('공개 곡이 없어도 프로필과 섹션별 0곡을 표시하고 미등록 링크를 추정하지 않는다', async ({
  page,
}) => {
  await page.route(`**/api/v1/members/${member.id}`, (route) =>
    route.fulfill({
      json: {
        member: {
          ...member,
          unitName: null,
          generation: null,
          chzzkUrl: null,
          xUrl: null,
        },
        channels: [],
      },
    }),
  )
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) =>
    route.fulfill({ json: emptyPage }),
  )
  await page.goto(`/members/${member.id}`)
  await expect(
    page.getByRole('heading', { name: member.name, exact: true }),
  ).toBeVisible()
  for (const label of ['전체', '오리지널', '커버 전체']) {
    const section = page.getByRole('region', {
      name: `${label} 곡 목록`,
      exact: true,
    })
    await expect(section.getByRole('heading', { level: 2 })).toHaveText(
      `${label} 0곡`,
    )
    await expect(
      section.getByText('등록된 공개 곡이 없습니다', { exact: true }),
    ).toBeVisible()
  }
  await expect(page.getByLabel('공식 채널 링크')).toHaveCount(0)
})

test('졸업 멤버·복수 채널·긴 이름·이미지 실패를 처리하고 위험한 링크는 숨긴다', async ({
  page,
}) => {
  await page.route('**/broken-profile.png', (route) =>
    route.fulfill({ status: 404 }),
  )
  const name = '긴 멤버 이름'.repeat(8)
  await page.route(`**/api/v1/members/${graduate.id}`, (route) =>
    route.fulfill({
      json: {
        member: {
          ...graduate,
          name,
          profileImageUrl: '/broken-profile.png',
          chzzkUrl: 'javascript:alert(1)',
          xUrl: 'not-a-url',
        },
        channels: [
          {
            name: '공식 채널',
            youtubeId: 'one',
            url: 'https://www.youtube.com/channel/one',
          },
          {
            name: '음악 채널',
            youtubeId: 'two',
            url: 'https://www.youtube.com/channel/two',
          },
        ],
      },
    }),
  )
  await page.route(`**/api/v1/members/${graduate.id}/songs?*`, (route) =>
    route.fulfill({ json: memberSongs }),
  )
  await page.goto(`/members/${graduate.id}`)
  await expect(
    page.getByRole('heading', { name: `${name} (졸업)`, exact: true }),
  ).toBeVisible()
  await expect(page.getByText('GRADUATE', { exact: true })).toBeVisible()
  const links = page.getByLabel('공식 채널 링크')
  await expect(links.getByRole('link')).toHaveCount(2)
  await expect(
    links.getByRole('link', { name: 'YouTube · 음악 채널', exact: true }),
  ).toHaveAttribute('href', 'https://www.youtube.com/channel/two')
  await expect(page.locator('a[href^="javascript:"]')).toHaveCount(0)
  await expect(page.locator('img[src="/broken-profile.png"]')).toHaveCount(0)
  const viewport = page.viewportSize()!
  for (const width of [viewport.width, 320]) {
    await page.setViewportSize({ ...viewport, width })
    for (let i = 0; i < 2; i++) {
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true)
      await page.getByRole('button', { name: '메뉴 열기' }).click()
      await page.getByRole('button', { name: /모드로 변경$/ }).click()
      await page.keyboard.press('Escape')
    }
  }
})

test('멤버 상세와 곡 목록은 각각 로딩하고 곡 오류에서도 프로필을 유지한다', async ({
  page,
}) => {
  let releaseDetail!: () => void
  let releaseSongs!: () => void
  const detailGate = new Promise<void>((resolve) => {
    releaseDetail = resolve
  })
  const songsGate = new Promise<void>((resolve) => {
    releaseSongs = resolve
  })
  let fail = true
  await page.route(`**/api/v1/members/${member.id}`, async (route) => {
    await detailGate
    await route.fulfill({ json: memberDetail })
  })
  await page.route(`**/api/v1/members/${member.id}/songs?*`, async (route) => {
    await songsGate
    await route.fulfill(
      fail
        ? { status: 400, json: { code: 'INVALID_CATALOG_QUERY' } }
        : { json: memberSongs },
    )
  })
  try {
    await page.goto(`/members/${member.id}`)
    await expect(
      page.getByRole('status', { name: '멤버 상세 불러오는 중' }),
    ).toBeVisible()
    releaseDetail()
    await expect(
      page.getByRole('heading', { name: member.name, exact: true }),
    ).toBeVisible()
    const section = page.getByRole('region', {
      name: '커버 전체 곡 목록',
      exact: true,
    })
    await expect(
      section.getByRole('status', { name: '노래 불러오는 중' }),
    ).toBeVisible()
    releaseSongs()
    await expect(
      section.getByText('노래를 불러오지 못했어요', { exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('heading', { name: member.name, exact: true }),
    ).toBeVisible()
    fail = false
    await section
      .getByRole('button', { name: '다시 시도', exact: true })
      .click()
    await expect(section.getByRole('article')).toHaveCount(2)
  } finally {
    releaseDetail()
    releaseSongs()
  }
})

test('잘못된 URL 필터는 곡 요청을 막고 초기화 후 기본 목록을 조회한다', async ({
  page,
}) => {
  let calls = 0
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    calls++
    return route.fulfill({ json: memberSongs })
  })
  await page.goto(`/members/${member.id}?multipleParticipants=invalid`)
  await expect(
    page.getByText('곡 조회 조건이 올바르지 않습니다', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page).toHaveURL(`/members/${member.id}`)
  await expect(page.getByRole('article')).toHaveCount(6)
  expect(calls).toBe(3)
})

test('펼친 섹션에서 곡 상세로 갔다 돌아오면 커버 조건과 받은 곡을 복원한다', async ({
  page,
}) => {
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    if (params.get('type') !== 'COVER')
      return route.fulfill({ json: memberSongs })
    cursors.push(params.get('cursor'))
    return route.fulfill({
      json: params.has('cursor')
        ? { ...emptyPage, items: [memberSong(21)], totalCount: 21 }
        : {
            items: Array.from({ length: 20 }, (_, i) => memberSong(i + 1)),
            hasNext: true,
            nextCursor: 'cover-next',
            totalCount: 21,
          },
    })
  })
  await page.goto(`/members/${member.id}?multipleParticipants=true&type=COVER`)
  const cover = page.getByRole('region', {
    name: '커버 듀엣 및 단체 곡 목록',
    exact: true,
  })
  await cover
    .getByRole('button', {
      name: '커버 듀엣 및 단체 곡 모두 펼치기',
      exact: true,
    })
    .click()
  await expect(cover.getByRole('article')).toHaveCount(21)
  await cover
    .getByRole('link', { name: '테스트 노래 21 상세 보기', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다', exact: true }),
  ).toBeVisible()
  await page.goBack()
  await expect(
    page.getByRole('button', { name: '듀엣 및 단체', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await cover
    .getByRole('button', {
      name: '커버 듀엣 및 단체 곡 모두 펼치기',
      exact: true,
    })
    .click()
  await expect(cover.getByRole('article')).toHaveCount(21)
  expect(cursors).toEqual([null, 'cover-next'])
})
