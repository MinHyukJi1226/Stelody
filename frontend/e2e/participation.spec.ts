import { test, expect, emptyPage, makeSong } from './fixtures'
import { detail, makeViews } from './song-detail-fixtures'
import { member, memberDetail, memberSong } from './member-fixtures'

const participants = [
  {
    id: member.id,
    name: member.name,
    kind: 'MEMBER' as const,
    activityStatus: 'ACTIVE' as const,
  },
  {
    id: 'external',
    name: '외부 참여자',
    kind: 'EXTERNAL' as const,
    activityStatus: null,
  },
]

test('전체곡의 작은 카드는 유형·제목·참여자를 표시하고 상세 배지를 추가하지 않는다', async ({
  page,
}) => {
  const songs = [
    makeSong(1, { title: '다인 참여곡', participants }),
    makeSong(2, {
      title: '생일곡',
      participants,
      isSpecialEvent: true,
      specialEventLabel: '생일 기념',
    }),
    makeSong(3, {
      title: '게임 협업곡',
      isSpecialEvent: true,
      specialEventLabel: '게임 협업',
    }),
  ]
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: songs, totalCount: 3 } }),
  )
  await page.goto('/songs')
  await expect(page.getByRole('article')).toHaveCount(3)
  const multi = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '다인 참여곡', exact: true }),
  })
  await expect(multi).toContainText('외부 참여자')
  await expect(page.getByText('콜라보', { exact: true })).toHaveCount(0)
  await expect(page.getByText('생일 기념', { exact: true })).toHaveCount(0)
  await expect(page.getByText('게임 협업', { exact: true })).toHaveCount(0)
})

test('상세 배지는 참여 인원과 무관하게 특별 목적 설정과 등록 문구를 따른다', async ({
  page,
}) => {
  let special = true
  let label: string | null = '게임 협업'
  await page.route(`**/api/v1/songs/${detail.song.id}`, (route) =>
    route.fulfill({
      json: {
        ...detail,
        song: {
          ...detail.song,
          participants,
          multipleParticipants: true,
          isSpecialEvent: special,
          specialEventLabel: label,
          collaboration: true,
        },
        relatedSongs: [],
      },
    }),
  )
  await page.route(`**/api/v1/songs/${detail.song.id}/views?*`, (route) =>
    route.fulfill({ json: makeViews() }),
  )
  await page.goto(`/songs/${detail.song.id}`)
  const heading = page.locator('main header')
  await expect(heading.getByText('게임 협업', { exact: true })).toBeVisible()
  await expect(page.getByText('콜라보', { exact: true })).toHaveCount(0)
  label = null
  await page.reload()
  await expect(heading.getByText('특별한 날', { exact: true })).toBeVisible()
  await expect(page.getByText('콜라보', { exact: true })).toHaveCount(0)
  special = false
  label = '표시하지 않을 문구'
  await page.reload()
  await expect(
    page.getByRole('heading', { name: detail.song.title, exact: true }),
  ).toBeVisible()
  await expect(
    heading.getByText('표시하지 않을 문구', { exact: true }),
  ).toHaveCount(0)
})

test('기존 다인 참여 주소를 커버 필터로 복원하고 충돌하는 주소는 조회하지 않는다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  const originals = Array.from({ length: 3 }, (_, index) =>
    makeSong(index + 1, { type: 'ORIGINAL', participants }),
  )
  const multipleCovers = Array.from({ length: 4 }, (_, index) =>
    makeSong(index + 4, { participants }),
  )
  const covers = [
    ...multipleCovers,
    ...Array.from({ length: 5 }, (_, index) => memberSong(index + 8)),
  ]
  await page.route(`**/api/v1/members/${member.id}`, (route) =>
    route.fulfill({ json: memberDetail }),
  )
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    expect(params.has('collaboration')).toBe(false)
    expect(params.has('multipleParticipants')).toBe(false)
    requests.push(params)
    const type = params.get('type')
    const items =
      type === 'ORIGINAL'
        ? originals
        : type === 'COVER'
          ? params.get('participation') === 'MULTIPLE'
            ? multipleCovers
            : covers
          : [...originals, ...covers]
    return route.fulfill({
      json: { ...emptyPage, items, totalCount: items.length },
    })
  })

  await page.goto(`/members/${member.id}?collaboration=true`)
  const filtered = page.getByRole('region', {
    name: '커버 듀엣 및 단체 곡 목록',
  })
  await expect(filtered.getByRole('heading', { level: 2 })).toHaveText(
    '커버 듀엣 및 단체 4곡',
  )
  await expect(page.getByRole('heading', { name: '전체 12곡' })).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '오리지널 3곡' }),
  ).toBeVisible()
  expect(requests.map((p) => [p.get('type'), p.get('participation')])).toEqual(
    expect.arrayContaining([
      ['ALL', 'ALL'],
      ['ORIGINAL', 'ALL'],
      ['COVER', 'MULTIPLE'],
    ]),
  )
  const toggle = filtered.getByRole('button', {
    name: '듀엣 및 단체',
    exact: true,
  })
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')
  await toggle.click()

  await expect(
    page.getByRole('heading', { name: '커버 전체 9곡' }),
  ).toBeVisible()
  expect(requests.at(-1)?.get('type')).toBe('COVER')
  expect(requests.at(-1)?.get('participation')).toBe('ALL')
  expect(new URL(page.url()).searchParams.has('collaboration')).toBe(false)

  const calls = requests.length
  await page.goto(
    `/members/${member.id}?multipleParticipants=true&collaboration=false`,
  )
  await expect(
    page.getByText('곡 조회 조건이 올바르지 않습니다', { exact: true }),
  ).toBeVisible()
  expect(requests).toHaveLength(calls)
})
