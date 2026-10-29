import { test, expect } from './fixtures'
import type { Page } from '@playwright/test'
import { mockSongEditor } from './admin-song-fixtures'
import { makeAdminSong, sid, mid } from '../src/test/admin-song'
import type { SongInput } from '../src/features/admin/songs'

const original = '/admin/songs?q=노래&page=1&size=10'
const path = `/admin/songs/new?returnTo=${encodeURIComponent(original)}`
async function setup(page: Page) {
  await mockSongEditor(page)
  const state = {
    posts: [] as SongInput[],
    mode: 'success',
    gate: Promise.resolve(),
    duplicates: [] as string[],
  }
  await page.route('**/api/v1/admin/songs', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON() as SongInput
    state.posts.push(body)
    expect(route.request().headers()['x-csrf-token']).toBe('test-csrf')
    await state.gate
    if (state.mode === 'lost') return route.abort()
    if (state.mode === 'server')
      return route.fulfill({ status: 500, json: { code: 'SERVER_ERROR' } })
    if (state.mode === 'denied')
      return route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    if (state.mode === 'invalid')
      return route.fulfill({
        status: 400,
        json: {
          code: 'INVALID_CATALOG_REQUEST',
          title: '입력 확인',
          fieldErrors: [
            { field: 'title', message: '곡명 재확인', code: 'INVALID' },
            {
              field: 'links[0].url',
              message: 'HTTPS 링크를 입력해주세요',
              code: 'INVALID',
            },
          ],
        },
      })
    if (state.mode === 'malformed') return route.fulfill({ json: {} })
    return route.fulfill({
      status: 201,
      json: {
        item: {
          ...makeAdminSong(),
          ...body,
          version: 0,
          workId: body.workId ?? null,
          representativeVideoId: null,
          videos: [],
          searchCheckedAt: null,
          recommendedSearchQuery: body.recommendedSearchQuery ?? null,
          links: body.links.map((x) => ({
            ...x,
            id: sid,
            checkedAt: '2026-10-29T00:00:00Z',
          })),
          karaoke: body.karaoke.map((x) => ({
            ...x,
            id: sid,
            number: x.number ?? null,
            sourceUrl: x.sourceUrl ?? null,
            checkedAt: null,
          })),
        },
        possibleDuplicateIds: state.duplicates,
      },
    })
  })
  return state
}
async function fill(page: Page) {
  await page.getByLabel('곡명*', { exact: true }).fill('새 곡 초안')
  await page.getByLabel('등록 사유*', { exact: true }).fill('관리자 수동 등록')
}
test('목록에서 진입하고 성공 후 원래 검색어·페이지로 돌아간다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(original)
  await page.getByRole('link', { name: '노래 수동 등록', exact: true }).click()
  await expect(page).toHaveURL((url) => {
    const destination = new URL(url.searchParams.get('returnTo')!, url.origin)
    return (
      url.pathname === '/admin/songs/new' &&
      destination.pathname === '/admin/songs' &&
      destination.searchParams.get('q') === '노래' &&
      destination.searchParams.get('page') === '1' &&
      destination.searchParams.get('size') === '10'
    )
  })
  await fill(page)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장했습니다', exact: true }),
  ).toBeVisible()
  expect(state.posts).toHaveLength(1)
  expect(state.posts[0]).toMatchObject({
    version: 0,
    title: '새 곡 초안',
    visibility: 'DRAFT',
    memberIds: [],
    externalArtistIds: [],
    aliases: [],
  })
  expect(state.posts[0]).not.toHaveProperty('representativeVideoId')
  await page.getByRole('link', { name: '원래 목록으로', exact: true }).click()
  await expect(page).toHaveURL(original)
})
test('등록 전에 필수 입력을 확인하고 초안을 유지한다', async ({ page }) => {
  const state = await setup(page)
  await page.goto(path)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByText('곡명을 입력해주세요.', { exact: true }),
  ).toBeVisible()
  expect(state.posts).toHaveLength(0)
  await fill(page)
  await page.getByRole('button', { name: '커버', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '오리지널', exact: true })
    .click()
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장했습니다' }),
  ).toBeVisible()
  expect(state.posts[0]!.type).toBe('ORIGINAL')
})
test('멤버 선택과 별칭을 등록 본문에 반영한다', async ({ page }) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByLabel('검색 별칭 · 선택', { exact: true }).fill('한글 발음')
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
  await page.getByRole('dialog').getByRole('checkbox').first().check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장했습니다' }),
  ).toBeVisible()
  expect(state.posts[0]!.memberIds).toEqual([mid])
  expect(state.posts[0]!.aliases).toEqual(['한글 발음'])
})
test('추가하지 않은 별칭과 중복 별칭을 버리지 않고 안내한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  const field = page.getByLabel('검색 별칭 · 선택', { exact: true })
  await field.fill('Milky Way')
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByText('입력 중인 별칭을 추가하거나 비운 뒤 등록해주세요.'),
  ).toBeVisible()
  expect(state.posts).toHaveLength(0)
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await field.fill('milky   way')
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await expect(
    page.getByText('공백·대소문자가 같은 별칭이 있습니다.'),
  ).toBeVisible()
})
for (const mode of ['lost', 'server', 'malformed'])
  test(`${mode}: 응답을 확정할 수 없으면 재등록을 막고 입력을 보존한다`, async ({
    page,
  }) => {
    const state = await setup(page)
    state.mode = mode
    await page.goto(path)
    await fill(page)
    await page.getByRole('button', { name: '등록', exact: true }).click()
    await expect(
      page.getByRole('heading', { name: '저장 결과를 확인할 수 없습니다' }),
    ).toBeVisible()
    await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
      '새 곡 초안',
    )
    await expect(
      page.getByRole('button', { name: '등록', exact: true }),
    ).toBeDisabled()
    expect(state.posts).toHaveLength(1)
    await page
      .getByRole('button', { name: '노래 목록에서 확인', exact: true })
      .click()
    await expect(page.getByRole('dialog')).toContainText(
      '이미 등록됐을 수 있습니다.',
    )
    await page
      .getByRole('button', { name: '저장하지 않고 이동', exact: true })
      .click()
    await expect(page).toHaveURL(original)
    expect(state.posts).toHaveLength(1)
  })
test('저장 중 중복 실행과 이동을 막고 성공 전에 완료를 표시하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(page.getByText('저장 중입니다', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '저장했습니다' })).toHaveCount(
    0,
  )
  await expect(page.getByLabel('곡명*', { exact: true })).toBeDisabled()
  await page
    .getByRole('navigation', { name: '관리자 메뉴' })
    .getByRole('link', { name: '작업함', exact: true })
    .click()
  await expect(page).toHaveURL(path)
  release()
  await expect(
    page.getByRole('heading', { name: '저장했습니다' }),
  ).toBeVisible()
  expect(state.posts).toHaveLength(1)
})
test('미저장 이동 확인을 취소하면 입력을 유지한다', async ({ page }) => {
  await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText(
    '저장하지 않은 입력은 버려집니다.',
  )
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '새 곡 초안',
  )
})
test('서버 필드 오류를 표시하고 같은 입력으로 수정할 수 있다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'invalid'
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '링크 추가', exact: true }).click()
  await page.getByLabel('플랫폼*', { exact: true }).fill('YouTube')
  await page.getByLabel('링크 URL*', { exact: true }).fill('http://example.com')
  await page
    .getByLabel('근거 URL*', { exact: true })
    .fill('https://example.com')
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(page.getByText('곡명 재확인', { exact: true })).toBeVisible()
  await expect(
    page.getByText('HTTPS 링크를 입력해주세요', { exact: true }),
  ).toBeVisible()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '새 곡 초안',
  )
  await expect(
    page.getByRole('button', { name: '등록', exact: true }),
  ).toBeEnabled()
  expect(state.posts).toHaveLength(1)
})
test('권한 오류에는 공통 관리자 접근 안내를 표시한다', async ({ page }) => {
  const state = await setup(page)
  state.mode = 'denied'
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  await expect(page.getByRole('heading', { name: '저장했습니다' })).toHaveCount(
    0,
  )
  expect(state.posts).toHaveLength(1)
})
test('특별 목적·검색 어려움은 추가 필수 입력을 확인한다', async ({ page }) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '사용하지 않음', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '사용', exact: true })
    .click()
  await page
    .getByRole('button', { name: '미확인', exact: true })
    .first()
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '검색 어려움', exact: true })
    .click()
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByText('특별 목적 표시 문구를 입력해주세요.'),
  ).toBeVisible()
  await expect(page.getByText('권장 검색어를 입력해주세요.')).toBeVisible()
  expect(state.posts).toHaveLength(0)
  await page
    .getByLabel('특별 목적 표시 문구*', { exact: true })
    .fill('생일 기념')
  await page.getByLabel('권장 검색어*', { exact: true }).fill('공식 검색어')
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장했습니다' }),
  ).toBeVisible()
  expect(state.posts[0]).toMatchObject({
    isSpecialEvent: true,
    specialEventLabel: '생일 기념',
    searchVisibility: 'DIFFICULT',
    recommendedSearchQuery: '공식 검색어',
  })
})
test('PC·모바일과 양쪽 테마에서 등록 화면이 가로로 넘치지 않는다', async ({
  page,
}, info) => {
  await setup(page)
  await page.goto(path)
  await fill(page)
  for (const theme of ['light', 'dark']) {
    if ((await page.locator('html').getAttribute('data-theme')) !== theme)
      await page
        .getByRole('button', {
          name: theme === 'dark' ? '다크 모드로 변경' : '라이트 모드로 변경',
          exact: true,
        })
        .click()
    await page.evaluate(() => window.scrollTo(0, 0))
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: info.outputPath(`create-${theme}.png`),
      fullPage: true,
      animations: 'disabled',
    })
  }
})
