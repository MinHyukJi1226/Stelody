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
  await expect(
    page.getByRole('heading', { name: '목소리로 찾아보세요' }),
  ).toBeVisible()
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
  await expect(page.getByText('Universe · 2기', { exact: true })).toBeVisible()
  const counts = page.getByRole('definition')
  await expect(counts).toHaveText(['12', '3', '9'])
  await expect(
    page.getByText('공동 참여 4곡 · 전체 12곡에 포함됩니다.', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '공식 YouTube ↗', exact: true }),
  ).toHaveAttribute('href', memberDetail.channels[0]!.url)
  await expect(
    page.getByRole('link', { name: '치지직 채널 ↗', exact: true }),
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

test('필터·정렬을 URL과 API에 적용하고 새로고침·뒤로 가기에서 복원한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  let detailCalls = 0
  await page.route(`**/api/v1/members/${member.id}`, (route) => {
    detailCalls++
    return route.fulfill({ json: memberDetail })
  })
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const p = new URL(route.request().url()).searchParams
    requests.push(p)
    return route.fulfill({ json: memberSongs })
  })
  await page.goto(
    `/members/${member.id}?type=COVER&collaboration=true&sort=VIEWS`,
  )
  await expect(page.getByRole('article')).toHaveCount(2)
  await expect(
    page.getByRole('tab', { name: '커버', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(
    page.getByRole('button', { name: '공동 참여곡만', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByLabel('참여곡 정렬')).toHaveValue('VIEWS')
  expect(Object.fromEntries(requests[0]!)).toEqual({
    type: 'COVER',
    collaboration: 'true',
    sort: 'VIEWS',
    size: '20',
  })
  await page.getByRole('tab', { name: '오리지널', exact: true }).click()
  await expect.poll(() => requests.at(-1)?.get('type')).toBe('ORIGINAL')
  expect(requests.at(-1)?.get('cursor')).toBeNull()
  await page.getByLabel('참여곡 정렬').selectOption('LATEST')
  await expect.poll(() => requests.at(-1)?.get('sort')).toBe('LATEST')
  await page.getByRole('button', { name: '공동 참여곡만', exact: true }).click()
  await expect.poll(() => requests.at(-1)?.get('collaboration')).toBe('false')
  expect(detailCalls).toBe(1)
  await page.goBack()
  await expect(
    page.getByRole('button', { name: '공동 참여곡만', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.reload()
  await expect(
    page.getByRole('tab', { name: '오리지널', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('참여곡 정렬')).toHaveValue('LATEST')
})

test('추가 조회 실패는 기존 곡을 유지하고 같은 커서를 재시도한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  let fail = true
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const p = new URL(route.request().url()).searchParams
    cursors.push(p.get('cursor'))
    if (p.has('cursor') && fail)
      return route.fulfill({
        status: 400,
        json: { code: 'INVALID_CATALOG_QUERY' },
      })
    return route.fulfill({
      json: p.has('cursor')
        ? {
            ...emptyPage,
            items: [memberSong(20), memberSong(21)],
            totalCount: 21,
          }
        : {
            items: Array.from({ length: 20 }, (_, i) => memberSong(i + 1)),
            hasNext: true,
            nextCursor: 'songs+/=&next',
            totalCount: 21,
          },
    })
  })
  await page.goto(`/members/${member.id}?type=COVER`)
  await expect(page.getByRole('article')).toHaveCount(20)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByText('노래를 더 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(20)
  fail = false
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(21)
  expect(cursors).toEqual([null, 'songs+/=&next', 'songs+/=&next'])
})

test('조회수 집계가 바뀌면 같은 필터의 첫 페이지부터 다시 조회한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })
  let changed = false
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const p = new URL(route.request().url()).searchParams
    expect(p.get('sort')).toBe('VIEWS')
    expect(p.get('type')).toBe('COVER')
    expect(p.get('collaboration')).toBe('true')
    cursors.push(p.get('cursor'))
    if (p.has('cursor')) {
      changed = true
      return route.fulfill({
        status: 409,
        json: { code: 'VIEW_PUBLICATION_CHANGED' },
      })
    }
    return route.fulfill({
      json: changed
        ? { ...emptyPage, items: [memberSong(3)], totalCount: 1 }
        : { ...memberSongs, hasNext: true, nextCursor: 'old-publication' },
    })
  })
  await page.goto(
    `/members/${member.id}?type=COVER&collaboration=true&sort=VIEWS`,
  )
  await expect(page.getByRole('article')).toHaveCount(2)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(
    page.getByText(
      '조회수 집계가 갱신되었습니다. 다시 시도하면 첫 페이지부터 불러옵니다.',
      { exact: true },
    ),
  ).toBeVisible()
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page.getByRole('article')).toHaveCount(1)
  await expect(
    page.getByRole('heading', { name: '테스트 노래 3', exact: true }),
  ).toBeVisible()
  expect(cursors).toEqual([null, 'old-publication', null])
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

test('공개 곡이 없어도 프로필·0곡 집계를 유지하고 미등록 프로필을 추정하지 않는다', async ({
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
          songCounts: { total: 0, originals: 0, covers: 0, collaborations: 0 },
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
  await expect(page.getByRole('definition')).toHaveText(['0', '0', '0'])
  await expect(
    page.getByRole('heading', {
      name: '등록된 공개 곡이 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(page.getByLabel('공식 채널 링크')).toHaveCount(0)
  await expect(page.getByText('Universe · 2기', { exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '커버', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '조건에 맞는 공개 곡이 없습니다',
      exact: true,
    }),
  ).toBeVisible()
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
  await expect(
    page.getByText('졸업 · 공개곡은 계속 만나볼 수 있어요.', { exact: true }),
  ).toBeVisible()
  const links = page.getByLabel('공식 채널 링크')
  await expect(links.getByRole('link')).toHaveCount(2)
  await expect(
    links.getByRole('link', { name: 'YouTube · 음악 채널 ↗', exact: true }),
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
      await page.getByRole('button', { name: /모드로 변경$/ }).click()
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
    await expect(
      page.getByRole('status', { name: '노래 불러오는 중' }),
    ).toBeVisible()
    releaseSongs()
    await expect(
      page.getByRole('heading', {
        name: '노래를 불러오지 못했어요',
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('heading', { name: member.name, exact: true }),
    ).toBeVisible()
    fail = false
    await page.getByRole('button', { name: '다시 시도', exact: true }).click()
    await expect(page.getByRole('article')).toHaveCount(2)
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
  await page.goto(`/members/${member.id}?collaboration=invalid`)
  await expect(
    page.getByText('곡 조회 조건이 올바르지 않습니다', { exact: true }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(page).toHaveURL(`/members/${member.id}`)
  await expect(page.getByRole('article')).toHaveCount(2)
  expect(calls).toBe(1)
})

test('스크롤로 추가 곡을 조회하고 곡 상세에서 뒤로 가면 필터·목록을 복원한다', async ({
  page,
}) => {
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const p = new URL(route.request().url()).searchParams
    cursors.push(p.get('cursor'))
    return route.fulfill({
      json: p.has('cursor')
        ? { ...emptyPage, items: [memberSong(21)], totalCount: 21 }
        : {
            items: Array.from({ length: 20 }, (_, i) => memberSong(i + 1)),
            hasNext: true,
            nextCursor: 'scroll-next',
            totalCount: 21,
          },
    })
  })
  await page.goto(`/members/${member.id}?type=COVER`)
  await expect(page.getByRole('article')).toHaveCount(20)
  await page
    .getByRole('button', { name: '노래 더 보기', exact: true })
    .scrollIntoViewIfNeeded()
  await expect(page.getByRole('article')).toHaveCount(21)
  await page
    .getByRole('link', { name: '테스트 노래 21 상세 보기', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다', exact: true }),
  ).toBeVisible()
  await page.goBack()
  await expect(
    page.getByRole('tab', { name: '커버', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('article')).toHaveCount(21)
  expect(cursors).toEqual([null, 'scroll-next'])
})
