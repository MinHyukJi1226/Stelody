import { test, expect, emptyPage, makeSong } from './fixtures'
import { detail, makeViews } from './song-detail-fixtures'
import { member, memberDetail, memberSongs } from './member-fixtures'

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

test('다인 참여곡에 콜라보 배지를 붙이지 않고 등록된 특별 목적 문구만 표시한다', async ({
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
  await expect(page.getByText('생일 기념', { exact: true })).toBeVisible()
  await expect(page.getByText('게임 협업', { exact: true })).toBeVisible()
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

test('기존 주소도 새 필터만 보내며 멤버 집계는 새 필드를 사용한다', async ({
  page,
}) => {
  const requests: URLSearchParams[] = []
  await page.route(`**/api/v1/members/${member.id}`, (route) =>
    route.fulfill({
      json: {
        ...memberDetail,
        member: {
          ...member,
          songCounts: {
            ...member.songCounts,
            collaborations: 99,
            multipleParticipants: 4,
          },
        },
      },
    }),
  )
  await page.route(`**/api/v1/members/${member.id}/songs?*`, (route) => {
    const params = new URL(route.request().url()).searchParams
    expect(params.has('collaboration')).toBe(false)
    requests.push(params)
    return route.fulfill({ json: memberSongs })
  })
  await page.goto(`/members/${member.id}?collaboration=true`)
  await expect(page.getByRole('article')).toHaveCount(2)
  expect(requests[0]!.get('multipleParticipants')).toBe('true')
  await expect(
    page.getByText('공동 참여 4곡 · 전체 12곡에 포함됩니다.', { exact: true }),
  ).toBeVisible()
  await expect(page.getByText(/공동 참여 99곡/)).toHaveCount(0)
  await page.getByRole('button', { name: '공동 참여곡만', exact: true }).click()
  await expect
    .poll(() => requests.at(-1)?.get('multipleParticipants'))
    .toBe('false')
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
