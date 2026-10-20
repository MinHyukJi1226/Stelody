import { test, expect, emptyPage } from './fixtures'
import { detail, song, makeViews } from './song-detail-fixtures'

test.beforeEach(async ({ page }) => {
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: detail }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({
      json: makeViews(
        Number(new URL(route.request().url()).searchParams.get('days')),
      ),
    }),
  )
})

test('목록에서 상세로 이동하고 곡·원곡 리소스를 구분하며 뒤로 복귀한다', async ({
  page,
}) => {
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: [song], totalCount: 1 } }),
  )
  await page.goto('/songs')
  await page.getByRole('link', { name: '테스트 커버 상세 보기' }).click()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '테스트 멤버 ›', exact: true }),
  ).toHaveAttribute('href', `/members/${song.participants[0]!.id}`)
  const own = page.getByLabel('노래방 번호', { exact: true }).first()
  await expect(own).toContainText('TJ · 00123')
  await expect(own).toContainText('KY · 미수록')
  const work = page.getByRole('region', { name: '원곡 정보', exact: true })
  await expect(work).toContainText('원곡 가수')
  await expect(work).toContainText('TJ · 00999')
  await expect(
    page.getByRole('link', { name: '관련 커버 상세 보기' }),
  ).toHaveAttribute('href', `/songs/${detail.relatedSongs[0]!.id}`)
  const related = page.getByRole('region', {
    name: '같은 원곡의 다른 커버',
    exact: true,
  })
  await expect(related.getByRole('article')).toHaveCount(2)
  await expect(
    related.getByRole('link', { name: '협업 커버 상세 보기' }),
  ).toHaveAttribute('href', `/songs/${detail.relatedSongs[1]!.id}`)
  expect(
    await related.getByRole('heading', { level: 3 }).allTextContents(),
  ).toEqual(['관련 커버', '협업 커버'])
  await page.goBack()
  await expect(page).toHaveURL('/songs')
  await expect(
    page.getByRole('link', { name: '테스트 커버 상세 보기' }),
  ).toBeVisible()
})

test('조회수 기간 변경·결측·0회·독립 오류와 재시도를 처리한다', async ({
  page,
}) => {
  const periods: number[] = []
  let fail = true
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) => {
    const days = Number(new URL(route.request().url()).searchParams.get('days'))
    periods.push(days)
    return days === 30 && fail
      ? route.fulfill({
          status: 400,
          json: { code: 'INVALID_STATISTICS_QUERY' },
        })
      : route.fulfill({ json: makeViews(days) })
  })
  await page.goto(`/songs/${song.id}`)
  const table = page.getByRole('table', { name: '날짜별 데이터' })
  await expect(table.getByRole('row')).toHaveCount(8)
  await expect(
    table.getByRole('row').filter({
      has: page.getByRole('button', {
        name: '2026-10-01 데이터 보기',
        exact: true,
      }),
    }),
  ).toContainText('0회')
  await expect(
    table.getByRole('row').filter({
      has: page.getByRole('button', {
        name: '2026-10-02 데이터 보기',
        exact: true,
      }),
    }),
  ).toContainText('미수집')
  const path = page.locator('.recharts-line-curve')
  await expect(path).toHaveCount(1)
  expect((await path.getAttribute('d'))?.match(/M/g)?.length).toBe(2)
  await page.getByRole('radio', { name: '최근 30일' }).click()
  await expect(
    page.getByText('조회수 추이를 불러오지 못했어요', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await expect(table.getByRole('button')).toHaveCount(0)
  fail = false
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(table.getByRole('row')).toHaveCount(31)
  expect(periods).toEqual([7, 30, 30])
})

test('사용자가 재생할 때만 YouTube 임베드를 로드한다', async ({ page }) => {
  await page.route('https://www.youtube-nocookie.com/**', (route) =>
    route.fulfill({ body: '<html></html>', contentType: 'text/html' }),
  )
  await page.goto(`/songs/${song.id}`)
  const play = page.getByRole('button', { name: '테스트 커버 영상 재생' })
  await expect(play).toBeVisible()
  await expect(page.locator('iframe')).toHaveCount(0)
  await play.click()
  await expect(page.locator('iframe')).toHaveAttribute(
    'src',
    `https://www.youtube-nocookie.com/embed/${detail.representativeVideo.youtubeId}?autoplay=1`,
  )
  await expect(
    page.getByRole('link', { name: 'YouTube 원본 보기 ↗' }),
  ).toHaveAttribute('href', detail.representativeVideo.url)
})

test('임베드 불가·오리지널·외부 참여자·안전한 링크를 처리한다', async ({
  page,
}) => {
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({
      json: {
        ...detail,
        song: {
          ...song,
          type: 'ORIGINAL',
          work: null,
          participants: [
            {
              ...song.participants[0],
              kind: 'EXTERNAL',
              activityStatus: null,
              name: '외부 가수',
            },
          ],
        },
        representativeVideo: {
          ...detail.representativeVideo,
          embeddable: false,
        },
        links: [
          ...detail.links,
          { platform: '잘못된 링크', url: 'javascript:alert(1)' },
        ],
        karaoke: [],
        relatedSongs: [],
      },
    }),
  )
  await page.goto(`/songs/${song.id}`)
  await expect(
    page.getByText('이 영상은 YouTube에서 시청해 주세요.', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: /영상 재생/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /잘못된 링크/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /외부 가수/ })).toHaveCount(0)
  await expect(
    page.getByRole('region', { name: '참여 멤버와 아티스트' }),
  ).toHaveCount(0)
  await expect(page.getByText('외부 가수', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('region', { name: 'YouTube 검색 도움말' }),
  ).toBeVisible()
  await expect(page.getByRole('heading', { name: '원곡 정보' })).toHaveCount(0)
  await expect(
    page.getByRole('heading', { name: '같은 원곡의 다른 커버' }),
  ).toHaveCount(0)
  await expect(page.getByLabel('노래방 번호', { exact: true })).toContainText(
    'TJ · 미확인',
  )
  await expect(
    page.getByRole('button', {
      name: `${song.title} 즐겨찾기 저장`,
      exact: true,
    }),
  ).toBeEnabled()
})

test('권장 검색어 복사 성공과 실패를 안내한다', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async (text: string) => {
          ;(window as Window & { copied?: string }).copied = text
        },
      },
    })
  })
  await page.goto(`/songs/${song.id}`)
  await page.getByRole('button', { name: '검색어 복사', exact: true }).click()
  await expect(
    page.getByText('검색어를 복사했습니다.', { exact: true }),
  ).toBeVisible()
  expect(
    await page.evaluate(() => (window as Window & { copied?: string }).copied),
  ).toBe(detail.searchHelp.recommendedQuery)
  await expect(
    page.getByRole('link', { name: 'YouTube 검색 ↗' }),
  ).toHaveAttribute(
    'href',
    `https://www.youtube.com/results?search_query=${encodeURIComponent(detail.searchHelp.recommendedQuery!)}`,
  )
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      value: {
        writeText: async () => {
          throw new Error('denied')
        },
      },
    })
  })
  await page.getByRole('button', { name: '검색어 복사', exact: true }).click()
  await expect(
    page.getByText(
      '복사하지 못했습니다. 권장 검색어를 직접 선택해 복사해 주세요.',
      { exact: true },
    ),
  ).toBeVisible()
})

test('유효하지 않은 주소·비공개 곡·요청 실패와 재시도를 처리한다', async ({
  page,
}) => {
  let calls = 0
  let status = 404
  await page.route(`**/api/v1/songs/${song.id}`, (route) => {
    calls++
    return status === 200
      ? route.fulfill({ json: detail })
      : route.fulfill({
          status,
          json: { code: status === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR' },
        })
  })
  await page.goto('/songs/invalid')
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다' }),
  ).toBeVisible()
  expect(calls).toBe(0)
  await page.goto(`/songs/${song.id}`)
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다' }),
  ).toBeVisible()
  expect(calls).toBe(1)
  status = 500
  await page.reload()
  await expect(
    page.getByText('노래 정보를 불러오지 못했습니다', { exact: true }),
  ).toBeVisible()
  status = 200
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
})

test('미수집·대표 영상 변경 시 잘못된 조회수를 표시하지 않는다', async ({
  page,
}) => {
  let changed = false
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({
      json: {
        ...detail,
        representativeVideo: {
          ...detail.representativeVideo,
          id: changed ? 'new-video' : detail.representativeVideo.id,
        },
      },
    }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({
      json: {
        ...makeViews(),
        videoId: 'new-video',
        latestViewCount: null,
        latestObservedAt: null,
        collectionStartedAt: null,
        points: makeViews().points.map((p) => ({
          ...p,
          viewCount: null,
          observedAt: null,
        })),
      },
    }),
  )
  await page.goto(`/songs/${song.id}`)
  await expect(
    page.getByText('대표 영상이 변경되었습니다', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('table', { name: '날짜별 데이터' }).getByRole('button'),
  ).toHaveCount(0)
  changed = true
  await page.getByRole('button', { name: '다시 시도', exact: true }).click()
  await expect(
    page.getByText('총 조회수 미수집', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('기간 내 수집된 데이터가 없습니다', { exact: true }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('table', { name: '날짜별 데이터' })
      .getByRole('columnheader'),
  ).toHaveCount(3)
})

test('긴 제목·다수 참여자·라이트/다크에서 모바일 가로 넘침이 없다', async ({
  page,
}) => {
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({
      json: {
        ...detail,
        song: {
          ...song,
          title: '긴노래제목'.repeat(12),
          participants: Array.from({ length: 8 }, (_, i) => ({
            ...song.participants[0],
            id: String(i),
            name: '참여자 이름 ' + i,
          })),
        },
      },
    }),
  )
  await page.goto(`/songs/${song.id}`)
  await expect(
    page.getByRole('heading', { name: '긴노래제목'.repeat(12), exact: true }),
  ).toBeVisible()
  for (let i = 0; i < 2; i++) {
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    await page.getByRole('button', { name: /모드로 변경$/ }).click()
  }
})

test('상세와 조회수 로딩 상태를 각각 표시한다', async ({ page }) => {
  let releaseDetail!: () => void
  let releaseViews!: () => void
  const detailGate = new Promise<void>((resolve) => {
    releaseDetail = resolve
  })
  const viewsGate = new Promise<void>((resolve) => {
    releaseViews = resolve
  })
  await page.route(`**/api/v1/songs/${song.id}`, async (route) => {
    await detailGate
    await route.fulfill({ json: detail })
  })
  await page.route(`**/api/v1/songs/${song.id}/views?*`, async (route) => {
    await viewsGate
    await route.fulfill({ json: makeViews() })
  })
  try {
    await page.goto(`/songs/${song.id}`)
    await expect(
      page.getByRole('status', { name: '노래 상세 불러오는 중' }),
    ).toBeVisible()
    releaseDetail()
    await expect(
      page.getByRole('heading', { name: song.title, exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('status', { name: '조회수 불러오는 중' }),
    ).toBeVisible()
    releaseViews()
    await expect(
      page.getByRole('table', { name: '날짜별 데이터' }),
    ).toBeVisible()
  } finally {
    releaseDetail()
    releaseViews()
  }
})

test('다른 커버가 없으면 섹션 전체를 숨긴다', async ({ page }) => {
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: { ...detail, relatedSongs: [] } }),
  )
  await page.goto(`/songs/${song.id}`)
  const related = page.getByRole('region', {
    name: '같은 원곡의 다른 커버',
    exact: true,
  })
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await expect(related).toHaveCount(0)
  await expect(
    page.getByRole('heading', { name: '같은 원곡의 다른 커버', exact: true }),
  ).toHaveCount(0)
})

test('오리지널곡에도 API가 반환한 다른 멤버의 커버를 표시한다', async ({
  page,
}) => {
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: { ...detail, song: { ...song, type: 'ORIGINAL' } } }),
  )
  await page.goto(`/songs/${song.id}`)
  const related = page.getByRole('region', {
    name: '같은 원곡의 다른 커버',
    exact: true,
  })
  await expect(related.getByRole('article')).toHaveCount(
    detail.relatedSongs.length,
  )
})
