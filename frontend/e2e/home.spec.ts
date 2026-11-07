import { test, expect, makeSong, emptyPage } from './fixtures'

test('홈의 간결한 곡 카드를 표시하고 최신곡 전체 목록으로 이동한다', async ({
  page,
}) => {
  const songs = [
    makeSong(1, {
      title: '자정 이후 공개곡',
      multipleParticipants: true,
      participants: [
        {
          id: 'first',
          name: '첫 멤버',
          kind: 'MEMBER',
          activityStatus: 'ACTIVE',
        },
        {
          id: 'second',
          name: '둘째 멤버',
          kind: 'MEMBER',
          activityStatus: 'ACTIVE',
        },
      ],
      isSpecialEvent: true,
      specialEventLabel: '생일',
    }),
    makeSong(2),
  ]
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: songs, totalCount: 2 } }),
  )
  await page.goto('/')
  const latest = page.getByRole('region', {
    name: '최신 곡',
    exact: true,
  })
  await expect(latest.getByRole('article')).toHaveCount(2)
  await expect(latest.getByText('2026.10.15')).toHaveCount(0)
  await expect(latest.getByText('콜라보', { exact: true })).toHaveCount(0)
  await expect(latest.getByText('생일', { exact: true })).toHaveCount(0)
  await expect(latest.getByText('원곡 · 정보 준비 중')).toHaveCount(0)
  await expect(
    page.getByRole('region', { name: '현재 인기 급상승 중인 곡' }),
  ).toContainText('준비 중입니다.')
  await page.getByRole('link', { name: '최신곡 전체 보기' }).click()
  await expect(page).toHaveURL('/songs')
  await expect(page.getByText('공개일 최신순 · 총 2곡')).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '전체곡', exact: true }),
  ).toBeVisible()
})

test('검색 폼은 한글과 특수문자를 보존해 검색 주소로 이동한다', async ({
  page,
}) => {
  await page.goto('/')
  await page
    .getByRole('searchbox', { name: '곡명, 원곡, 멤버 검색' })
    .fill('노래 & 멤버')
  await page
    .getByRole('search', { name: '노래 검색' })
    .getByRole('button', { name: '검색', exact: true })
    .click()
  await expect(page).toHaveURL(/\/search\?q=/)
  expect(new URL(page.url()).searchParams.get('q')).toBe('노래 & 멤버')
})

test('추천 조회 실패 후 재시도로 추천곡을 표시한다', async ({ page }) => {
  let fail = true
  await page.route('**/api/v1/songs/recommendations?*', (route) =>
    route.fulfill(
      fail
        ? { status: 503, json: { code: 'SERVICE_UNAVAILABLE' } }
        : { json: { items: [makeSong(3, { title: '추천곡' })] } },
    ),
  )
  await page.goto('/')
  const section = page.getByRole('region', { name: '오늘의 추천 곡' })
  await expect(section.getByText('추천을 불러오지 못했습니다')).toBeVisible()
  fail = false
  await section.getByRole('button', { name: '다시 시도' }).click()
  await expect(section.getByRole('heading', { name: '추천곡' })).toBeVisible()
})

test('최신곡 오류가 다른 영역을 막지 않고 재시도로 회복한다', async ({
  page,
}) => {
  let fail = true
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill(
      fail
        ? { status: 503, json: { code: 'UNAVAILABLE' } }
        : { json: { ...emptyPage, items: [makeSong(5)], totalCount: 1 } },
    ),
  )
  await page.route('**/api/v1/songs/recommendations?*', (route) =>
    route.fulfill({ json: { items: [makeSong(6)] } }),
  )
  await page.goto('/')
  const latest = page.getByRole('region', {
    name: '최신 곡',
    exact: true,
  })
  await expect(latest.getByText('노래를 불러오지 못했습니다')).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '테스트 노래 6' }),
  ).toBeVisible()
  fail = false
  await latest.getByRole('button', { name: '다시 시도' }).click()
  await expect(
    latest.getByRole('heading', { name: '테스트 노래 5' }),
  ).toBeVisible()
})

test('처음 불러오는 동안 스켈레톤을 표시하고 빈 결과를 안내한다', async ({
  page,
}) => {
  let release: () => void = () => {}
  const waiting = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/songs?*', async (route) => {
    await waiting
    await route.fulfill({ json: emptyPage })
  })
  await page.goto('/')
  const latest = page.getByRole('region', {
    name: '최신 곡',
    exact: true,
  })
  await expect(
    latest.getByRole('status', { name: '노래 불러오는 중' }),
  ).toBeVisible()
  release()
  await expect(latest.getByText('아직 공개된 노래가 없습니다')).toBeVisible()
})

test('추가 페이지 실패 후 기존 목록을 보존하고 같은 커서로 재시도한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Reflect.deleteProperty(window, 'IntersectionObserver')
  })
  const first = Array.from({ length: 20 }, (_, i) => makeSong(i + 10))
  let fail = true
  const cursors: (string | null)[] = []
  await page.route('**/api/v1/songs?*', (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    cursors.push(cursor)
    return route.fulfill(
      cursor
        ? fail
          ? { status: 503, json: { code: 'UNAVAILABLE' } }
          : {
              json: {
                ...emptyPage,
                items: [first[0], makeSong(50)],
                totalCount: 21,
              },
            }
        : {
            json: {
              items: first,
              nextCursor: 'opaque+/=',
              hasNext: true,
              totalCount: 21,
            },
          },
    )
  })
  await page.goto('/songs')
  await expect(page.getByRole('article')).toHaveCount(20)
  await page.getByRole('button', { name: '노래 더 보기', exact: true }).click()
  await expect(page.getByText('노래를 더 불러오지 못했습니다')).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(20)
  fail = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByRole('article')).toHaveCount(21)
  await expect(page.getByText('모든 노래를 불러왔습니다.')).toBeVisible()
  expect(cursors[0]).toBe(null)
  expect(cursors.slice(1).every((cursor) => cursor === 'opaque+/=')).toBe(true)
})

test('멤버의 졸업 상태와 이미지 누락을 처리하고 화면이 넘치지 않는다', async ({
  page,
}) => {
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'member',
            name: '테스트 멤버 이름',
            activityStatus: 'GRADUATED',
            profileImageUrl: null,
            generation: 1,
            unitName: null,
            chzzkUrl: null,
            xUrl: null,
            songCounts: {
              total: 0,
              originals: 0,
              covers: 0,
              collaborations: 0,
              multipleParticipants: 0,
            },
          },
        ],
        nextCursor: null,
        hasNext: false,
      },
    }),
  )
  await page.goto('/members')
  await expect(
    page.getByRole('link', { name: '테스트 멤버 이름 (졸업)' }),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('스크롤로 다음 페이지를 불러오고 상세에서 돌아오면 목록과 위치를 유지한다', async ({
  page,
}) => {
  const first = Array.from({ length: 20 }, (_, i) => makeSong(i + 100))
  await page.route('**/api/v1/songs?*', (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    return route.fulfill({
      json: cursor
        ? { ...emptyPage, items: [makeSong(150)], totalCount: 21 }
        : {
            items: first,
            nextCursor: 'automatic',
            hasNext: true,
            totalCount: 21,
          },
    })
  })
  await page.goto('/songs')
  await expect(page.getByRole('article')).toHaveCount(20)
  await page
    .getByRole('button', { name: '노래 더 보기', exact: true })
    .scrollIntoViewIfNeeded()
  await expect(page.getByRole('article')).toHaveCount(21)
  const last = page.getByRole('link', { name: '테스트 노래 150 상세 보기' })
  await last.scrollIntoViewIfNeeded()
  const previousY = await page.evaluate(() => scrollY)
  await last.click()
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다', exact: true }),
  ).toBeVisible()
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(0)
  await page.goBack()
  await expect(page.getByRole('article')).toHaveCount(21)
  await expect
    .poll(() => page.evaluate((y) => Math.abs(scrollY - y), previousY))
    .toBeLessThan(5)
})

test('썸네일 로드가 실패해도 카드 정보와 화면 규격을 유지한다', async ({
  page,
}) => {
  await page.route('**/missing-thumbnail.jpg', (route) =>
    route.fulfill({ status: 404 }),
  )
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({
      json: {
        ...emptyPage,
        items: [makeSong(200, { thumbnailUrl: '/missing-thumbnail.jpg' })],
        totalCount: 1,
      },
    }),
  )
  await page.goto('/')
  const latest = page.getByRole('region', {
    name: '최신 곡',
    exact: true,
  })
  await expect(latest.getByText('이미지 준비 중')).toBeVisible()
  await expect(
    latest.getByRole('heading', { name: '테스트 노래 200' }),
  ).toBeVisible()
  const mobile = (page.viewportSize()?.width ?? 0) < 768
  await expect(page.locator('header')).toHaveCSS(
    'padding-left',
    mobile ? '20px' : '40px',
  )
  await expect(latest.getByRole('article')).toHaveCount(1)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('멤버 명단 전체 페이지로 유닛 전원을 판정하고 혼합곡을 축약한다', async ({
  page,
}) => {
  const names = ['히나', '마시로', '리제', '타비']
  const participants = names.map((name, index) => ({
    id: `member-${index}`,
    name,
    kind: 'MEMBER' as const,
    activityStatus: 'ACTIVE' as const,
  }))
  const songs = [
    makeSong(1, { title: '유닛 전원곡', participants }),
    makeSong(2, {
      title: '유닛 일부곡',
      participants: participants.slice(0, 3),
    }),
    makeSong(3, {
      title: '혼합 단체곡',
      participants: [
        { ...participants[0]!, id: 'yuni', name: '아야츠노 유니' },
        ...participants,
      ],
    }),
    makeSong(4, {
      title: '솔로 오리지널',
      type: 'ORIGINAL',
      participants: participants.slice(0, 1),
    }),
    makeSong(5, {
      title: '듀엣 오리지널',
      type: 'ORIGINAL',
      participants: participants.slice(0, 2),
    }),
    makeSong(6, {
      title: '삼인 오리지널',
      type: 'ORIGINAL',
      participants: participants.slice(0, 3),
    }),
    makeSong(7, {
      title: '유닛 오리지널',
      type: 'ORIGINAL',
      participants,
    }),
    makeSong(8, {
      title: '점묘의 노래',
      publishedAt: '2024-12-01T12:00:00+09:00',
      participants: ['아야츠노 유니', '아이리 칸나'].map((name) => ({
        ...participants[0]!,
        id: name,
        name,
      })),
    }),
    makeSong(9, {
      title: 'Alice in Musicland',
      publishedAt: '2024-11-24T12:00:00+09:00',
      participants: [
        ...participants,
        ...['아야츠노 유니', '아이리 칸나'].map((name) => ({
          ...participants[0]!,
          id: name,
          name,
        })),
      ],
    }),
  ]
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({
      json: { ...emptyPage, items: songs, totalCount: songs.length },
    }),
  )
  let secondPage = false
  await page.route('**/api/v1/members?*', (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    secondPage ||= cursor === 'members-next'
    const members = participants.map((p) => ({
      id: p.id,
      name: p.name,
      generation: 2,
      unitName: 'Universe',
      activityStatus: 'ACTIVE',
      chzzkUrl: null,
      xUrl: null,
      profileImageUrl: null,
      songCounts: {
        total: 0,
        covers: 0,
        originals: 0,
        collaborations: 0,
        multipleParticipants: 0,
      },
    }))
    return route.fulfill({
      json: cursor
        ? {
            items: [
              ...members.slice(2),
              {
                ...members[0]!,
                id: 'third-member',
                name: '시부키',
                generation: 3,
                unitName: 'Cliché',
              },
            ],
            hasNext: false,
            nextCursor: null,
          }
        : {
            items: members.slice(0, 2),
            hasNext: true,
            nextCursor: 'members-next',
          },
    })
  })
  await page.goto('/songs')
  const full = page.getByRole('article').filter({ hasText: '유닛 전원곡' })
  await expect(full.getByText('Universe', { exact: true })).toBeVisible()
  expect(secondPage).toBe(true)
  await expect(
    page.getByRole('article').filter({ hasText: '유닛 일부곡' }),
  ).toContainText('히나 · 마시로 · 리제')
  await expect(
    page.getByRole('article').filter({ hasText: '혼합 단체곡' }),
  ).toContainText('아야츠노 유니 외 4명')
  for (const [title, label] of [
    ['솔로 오리지널', '히나'],
    ['듀엣 오리지널', '히나 · 마시로'],
    ['삼인 오리지널', '스텔라이브'],
    ['유닛 오리지널', 'Universe'],
    ['점묘의 노래', 'Mystic'],
    ['Alice in Musicland', 'Mystic · Universe'],
  ]) {
    const card = page.getByRole('article').filter({ hasText: title! })
    await expect(card.getByText(label!, { exact: true })).toBeVisible()
  }
})

test('멤버 명단 조회가 실패해도 단체곡 카드와 첫 참여자 축약을 유지한다', async ({
  page,
}) => {
  const participants = ['첫 멤버', '둘째', '셋째', '넷째'].map((name, i) => ({
    id: `member-${i}`,
    name,
    kind: 'MEMBER' as const,
    activityStatus: 'ACTIVE' as const,
  }))
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({
      json: {
        ...emptyPage,
        items: [makeSong(1, { participants })],
        totalCount: 1,
      },
    }),
  )
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({ status: 400, json: { code: 'UNAVAILABLE' } }),
  )
  await page.goto('/songs')
  await expect(page.getByRole('article')).toContainText('첫 멤버 외 3명')
})

test('메인 소식과 급상승을 API 값으로 표시하고 로그인 후 보관함을 표시한다', async ({
  page,
}, info) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await page.clock.install({ time: new Date('2026-11-05T18:00:00Z') })
  await page.route('**/test-home-thumbnail.svg', (route) =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#6450d8"/></svg>',
    }),
  )
  const song = makeSong(1, {
    title: '소식 테스트곡',
    thumbnailUrl: '/test-home-thumbnail.svg',
  })
  const songs = [
    song,
    ...Array.from({ length: 5 }, (_, index) =>
      makeSong(index + 2, { thumbnailUrl: song.thumbnailUrl }),
    ),
  ]
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({
      json: { ...emptyPage, items: songs, totalCount: songs.length },
    }),
  )
  await page.route('**/api/v1/songs/recommendations?*', (route) =>
    route.fulfill({ json: { items: songs } }),
  )
  const playlistId = '00000000-0000-4000-8000-000000000002'
  await page.route('**/api/v1/songs/milestones', (route) =>
    route.fulfill({
      json: {
        status: 'READY',
        checkedAt: '2026-11-06T00:00:00Z',
        approaching: [
          {
            song,
            target: 100000,
            viewCount: 99600,
            observedAt: '2026-11-06T00:00:00Z',
          },
        ],
        achievements: [
          { song, target: 100000, detectedAt: '2026-11-06T00:00:00Z' },
          {
            song: songs[1],
            target: 200000,
            detectedAt: '2026-11-06T00:00:00Z',
          },
        ],
      },
    }),
  )
  await page.route('**/api/v1/songs/upcoming', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            youtubeId: 'example',
            title: '예약 영상',
            channelName: '테스트 채널',
            scheduledStartAt: '2026-11-06T11:00:00Z',
            url: 'https://www.youtube.com/watch?v=example',
            thumbnailUrl: '/test-home-thumbnail.svg',
          },
          {
            youtubeId: 'next',
            title: '다음 예약 영상',
            channelName: '다음 채널',
            scheduledStartAt: '2026-11-07T10:00:00Z',
            url: 'https://www.youtube.com/watch?v=next',
          },
        ],
      },
    }),
  )
  await page.route('**/api/v1/songs/trending?size=10', (route) =>
    route.fulfill({
      json: { status: 'READY', items: [{ rank: 1, increase: 123123, song }] },
    }),
  )
  await page.goto('/')
  const news = page.getByRole('region', { name: '최신 소식' })
  await expect(
    news.getByRole('link', { name: /100,000회까지 400회 남았어요!/ }),
  ).toHaveAttribute('href', `/songs/${song.id}`)
  await expect(
    news.getByRole('link', { name: /100,000회를 달성했어요! 축하합니다!/ }),
  ).toBeVisible()
  const premieres = news.getByRole('group', { name: '최초 공개', exact: true })
  const premiere = premieres.getByRole('link', {
    name: '오늘 20:00 최초공개 예약 영상 테스트 채널',
  })
  await expect(premiere).toHaveAttribute(
    'href',
    'https://www.youtube.com/watch?v=example',
  )
  const thumbnail = premiere.locator('img')
  await expect(thumbnail).toHaveJSProperty('naturalWidth', 160)
  const expectedSize = info.project.name === 'mobile' ? [128, 72] : [250, 141]
  await expect(thumbnail).toHaveCSS('width', `${expectedSize[0]}px`)
  await expect(thumbnail).toHaveCSS('height', `${expectedSize[1]}px`)
  const nextPremiere = premieres.getByRole('link', {
    name: '11월 7일 19:00 최초공개 다음 예약 영상 다음 채널',
  })
  await expect(nextPremiere).toHaveAttribute(
    'href',
    'https://www.youtube.com/watch?v=next',
  )
  await expect(nextPremiere.locator('img')).toHaveCount(0)
  await expect(page.getByText('+123,123회')).toBeVisible()
  const rail = news.getByRole('group', { name: '조회수 달성' })
  const slide = () =>
    rail.evaluate((node) => Math.round(node.scrollTop / node.clientHeight))
  expect(await slide()).toBe(0)
  await page.clock.runFor(3000)
  await expect.poll(slide).toBe(1)
  await page.clock.runFor(3000)
  await expect.poll(slide).toBe(2)
  await expect
    .poll(() =>
      premieres.evaluate((node) =>
        Math.round(node.scrollTop / node.clientHeight),
      ),
    )
    .toBe(1)
  await page.clock.runFor(3000)
  await expect.poll(slide).toBe(1)
  const approach = news.getByRole('group', { name: '조회수 임박' })
  expect(await approach.evaluate((node) => node.scrollTop)).toBe(0)

  await expect(
    page.getByRole('region', { name: '내가 좋아하는 곡' }),
  ).toHaveCount(0)

  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      json: {
        id: '00000000-0000-4000-8000-000000000001',
        role: 'USER',
        status: 'ACTIVE',
        email: 'test@example.com',
      },
    }),
  )
  await page.route('**/api/v1/me/playlists?*', (route) =>
    route.fulfill({
      json: {
        ...emptyPage,
        items: [
          { id: playlistId, name: '내 음악', totalCount: 8, availableCount: 1 },
        ],
      },
    }),
  )
  await page.route('**/api/v1/me/playlists/*/items?*', (route) =>
    route.fulfill({
      json: { ...emptyPage, version: 1, items: [{ available: true, song }] },
    }),
  )
  await page.route('**/api/v1/me/favorites?*', (route) =>
    route.fulfill({
      json: {
        ...emptyPage,
        availableCount: 1,
        items: [{ songId: song.id, available: true, song }],
      },
    }),
  )
  await page.reload()
  await expect(page.getByRole('link', { name: '내 계정' })).toHaveAttribute(
    'href',
    '/account',
  )
  const playlist = page.getByRole('link', { name: /내 음악.*8곡/ })
  await expect(playlist).toHaveAttribute(
    'href',
    `/library/playlists/${playlistId}`,
  )
  await expect(playlist.locator('img')).toHaveCount(1)
  await expect(playlist.locator('img')).toHaveJSProperty('naturalWidth', 160)
  await expect(
    page
      .getByRole('region', { name: '내가 좋아하는 곡' })
      .getByRole('link', { name: '소식 테스트곡 상세 보기' }),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.screenshot({
    path: info.outputPath('home-light.png'),
    fullPage: true,
  })
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await expect(
    page.getByRole('link', { name: '좋아하는 곡', exact: true }),
  ).toHaveAttribute('href', '/library')
  await page.getByRole('button', { name: '다크 모드로 변경' }).click()
  await page.keyboard.press('Escape')
  await page.screenshot({
    path: info.outputPath('home-dark.png'),
    animations: 'disabled',
    fullPage: true,
  })
})
