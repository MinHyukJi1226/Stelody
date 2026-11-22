import { test, expect, emptyPage } from './fixtures'
import { detail, makeViews } from './song-detail-fixtures'

const youtubeId = detail.representativeVideo.youtubeId
const song = { ...detail.song, youtubeId }
const publicDetail = { ...detail, song }

test.beforeEach(async ({ page }) => {
  await page.route('https://www.youtube-nocookie.com/**', (route) =>
    route.fulfill({ body: '<html></html>', contentType: 'text/html' }),
  )
  await page.route(`**/api/v1/songs/by-youtube/${youtubeId}`, (route) =>
    route.fulfill({ json: publicDetail }),
  )
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: publicDetail }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({ json: makeViews() }),
  )
})

test('목록 링크와 직접 접속에 영상 ID를 사용하고 조회수는 곡 UUID로 조회한다', async ({
  page,
}) => {
  const requests: string[] = []
  page.on('request', (request) =>
    requests.push(new URL(request.url()).pathname),
  )
  await page.route('**/api/v1/songs?*', (route) =>
    route.fulfill({ json: { ...emptyPage, items: [song], totalCount: 1 } }),
  )
  await page.goto('/songs')
  const link = page.getByRole('link', { name: `${song.title} 상세 보기` })
  await expect(link).toHaveAttribute('href', `/songs/${youtubeId}`)
  await link.click()
  await expect(page).toHaveURL(`/songs/${youtubeId}`)
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('img', { name: /누적 조회수 선 그래프/ }),
  ).toBeVisible()
  expect(requests).toContain(`/api/v1/songs/by-youtube/${youtubeId}`)
  expect(requests).toContain(`/api/v1/songs/${song.id}/views`)
  expect(requests).not.toContain(`/api/v1/songs/${song.id}`)
})

test('기존 UUID 링크는 검색 조건과 해시를 보존해 영상 주소로 전환한다', async ({
  page,
}) => {
  await page.goto(`/songs/${song.id}?from=library#video`)
  await expect(page).toHaveURL(`/songs/${youtubeId}?from=library#video`)
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
})

test('이전 영상 링크도 현재 대표 영상 주소로 전환한다', async ({ page }) => {
  await page.route('**/api/v1/songs/by-youtube/oldVideo123', (route) =>
    route.fulfill({ json: publicDetail }),
  )
  await page.goto('/songs/oldVideo123')
  await expect(page).toHaveURL(`/songs/${youtubeId}`)
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
})

test('존재하지 않거나 비공개인 영상 주소는 곡을 노출하지 않는다', async ({
  page,
}) => {
  await page.goto('/songs/unknown1234')
  await expect(
    page.getByRole('heading', { name: '노래를 찾을 수 없습니다' }),
  ).toBeVisible()
  await expect(page.locator('iframe')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '최근 7일' })).toHaveCount(0)
})
