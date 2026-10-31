import { test, expect } from './fixtures'
import type { Page } from '@playwright/test'
import { mockSongEditor } from './admin-song-fixtures'
import { makeAdminSong, sid, mid, vid } from '../src/test/admin-song'
import type { VideoCandidate } from '../src/features/admin/song-videos'
import type { SongInput } from '../src/features/admin/songs'

const rid = '00000000-0000-4000-8000-000000000899'
const original = '/admin/songs?q=수동등록&page=1&size=10'
const path = `/admin/songs/${sid}/videos/new?returnTo=${encodeURIComponent(original)}`
async function setup(page: Page) {
  const editor = await mockSongEditor(page)
  editor.song = {
    ...makeAdminSong(),
    visibility: 'DRAFT',
    representativeVideoId: null,
    videos: [],
  }
  const state = {
    editor,
    creates: [] as SongInput[],
    registrations: [] as Record<string, unknown>[],
    pages: [] as number[],
    mode: 'success',
    gate: Promise.resolve(),
    candidate: {
      id: rid,
      version: 3,
      youtubeId: 'testVideo01',
      channelId: mid,
      channelName: '공식 채널',
      title: '선택한 공식 영상',
      publishedAt: '2026-10-01T00:00:00Z',
      thumbnailUrl: null,
      durationSeconds: 200,
      observedAt: '2026-10-28T00:00:00Z',
      availability: 'PUBLIC',
      disposition: 'REVIEW',
      suggestedType: 'COVER',
      ruleVersion: 'v1',
      decisionReason: '공식 커버',
      status: 'PENDING',
      reviewNote: null,
      firstSeenAt: '2026-10-28T00:00:00Z',
      sourceExpired: false,
      registeredVideoId: null,
      registeredSongId: null,
    } satisfies VideoCandidate as VideoCandidate,
  }
  await page.route('**/api/v1/admin/songs', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON() as SongInput
    state.creates.push(body)
    editor.song = {
      ...editor.song,
      ...body,
      version: 0,
      representativeVideoId: null,
      videos: [],
      workId: body.workId ?? null,
      recommendedSearchQuery: body.recommendedSearchQuery ?? null,
      karaoke: [],
      links: [],
    }
    return route.fulfill({
      status: 201,
      json: { item: editor.song, possibleDuplicateIds: [] },
    })
  })
  await page.route('**/api/v1/admin/reviews?*', async (route) => {
    const url = new URL(route.request().url())
    expect(url.searchParams.get('status')).toBe('PENDING')
    const pageNumber = Number(url.searchParams.get('page'))
    state.pages.push(pageNumber)
    if (state.mode === 'list-error')
      return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } })
    return route.fulfill({
      json: {
        items: state.mode === 'empty' ? [] : [state.candidate],
        page: pageNumber,
        size: 20,
        hasNext: pageNumber === 0,
      },
    })
  })
  await page.route(`**/api/v1/admin/reviews/${rid}`, (route) =>
    route.fulfill({ json: state.candidate }),
  )
  await page.route(
    `**/api/v1/admin/reviews/${rid}/registration`,
    async (route) => {
      const body = route.request().postDataJSON()
      state.registrations.push(body)
      expect(route.request().headers()['x-csrf-token']).toBe('test-csrf')
      await state.gate
      if (state.mode === 'invalid')
        return route.fulfill({
          status: 400,
          json: {
            code: 'INVALID_CATALOG_REQUEST',
            title: '입력 확인',
            fieldErrors: [
              {
                field: 'reason',
                code: 'INVALID',
                message: '연결 사유를 확인해주세요',
              },
            ],
          },
        })
      if (state.mode === 'conflict') {
        editor.song = { ...editor.song, title: '최신 곡명', version: 6 }
        state.candidate.version = 4
        return route.fulfill({
          status: 409,
          json: { code: 'CATALOG_VERSION_CONFLICT' },
        })
      }
      if (state.mode === 'denied')
        return route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
      const video = {
        ...makeAdminSong().videos[0]!,
        youtubeId: state.candidate.youtubeId,
        sourceTitle: state.candidate.title,
        kind: body.kind,
      }
      editor.song = {
        ...editor.song,
        version: editor.song.version + 1,
        videos: [video],
      }
      state.candidate = {
        ...state.candidate,
        status: 'REGISTERED',
        version: 4,
        registeredVideoId: vid,
      }
      if (state.mode === 'lost') return route.abort()
      if (state.mode === 'malformed')
        return route.fulfill({ json: { ...video, songId: mid } })
      return route.fulfill({ status: 201, json: video })
    },
  )
  return state
}
async function select(page: Page) {
  await page.getByRole('button', { name: '이 후보 선택', exact: true }).click()
  await page
    .getByLabel('연결 사유*', { exact: true })
    .fill('공식 커버 영상 연결')
}
async function confirm(page: Page) {
  await page.getByRole('button', { name: '연결 확인', exact: true }).click()
  await page.getByRole('button', { name: '확인 후 저장', exact: true }).click()
}
test('수동 등록에서 영상 연결·대표 지정·공개까지 완료하고 검색·페이지로 복귀한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(`/admin/songs/new?returnTo=${encodeURIComponent(original)}`)
  await page.getByLabel('곡명*', { exact: true }).fill('수동 등록 곡')
  await page.getByLabel('등록 사유*', { exact: true }).fill('수동 등록')
  await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
  await page.getByLabel('참여 멤버', { exact: true }).check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await page.getByRole('link', { name: '영상 연결 계속', exact: true }).click()
  await expect(page).toHaveURL(path)
  await select(page)
  await confirm(page)
  await expect(
    page.getByRole('heading', { name: '영상을 연결했습니다', exact: true }),
  ).toBeVisible()
  expect(state.registrations).toEqual([
    {
      version: 3,
      songId: sid,
      songVersion: 0,
      kind: 'OFFICIAL_COVER',
      reason: '공식 커버 영상 연결',
    },
  ])
  expect(state.editor.song.representativeVideoId).toBeNull()
  await page
    .getByRole('link', { name: '대표 영상·공개 설정', exact: true })
    .click()
  await page
    .getByRole('button', { name: '대표 영상 선택', exact: true })
    .click()
  await page.getByRole('radio', { name: /선택한 공식 영상/ }).check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await page.getByRole('tab', { name: '공개·검색', exact: true }).click()
  await page.getByRole('button', { name: '공개 설정', exact: true }).click()
  await page
    .getByLabel('변경 사유*', { exact: true })
    .fill('대표 영상 확인 후 공개')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page).toHaveURL(original)
  expect(state.creates).toHaveLength(1)
  expect(state.editor.puts).toHaveLength(1)
  expect(state.editor.puts[0]).toMatchObject({
    version: 1,
    title: '수동 등록 곡',
    representativeVideoId: vid,
    visibility: 'PUBLISHED',
    memberIds: [mid],
  })
  await page.reload()
  expect(state.editor.song.visibility).toBe('PUBLISHED')
})
test('기존 초안 수정에서도 영상 연결을 이어간다', async ({ page }) => {
  await setup(page)
  await page.goto(
    `/admin/songs/${sid}/edit?returnTo=${encodeURIComponent(original)}`,
  )
  await page
    .getByRole('link', { name: 'YouTube 영상 연결', exact: true })
    .click()
  await expect(page).toHaveURL(path)
  await expect(
    page.getByRole('heading', { name: '연결할 수집 후보' }),
  ).toBeVisible()
})
test('후보 목록 오류·페이지 유지·재조회와 빈 목록을 구분한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await expect(
    page.getByRole('button', { name: '다음', exact: true }),
  ).toBeEnabled()
  state.mode = 'list-error'
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '수집 후보를 조회하지 못했습니다' }),
  ).toBeVisible()
  state.mode = 'empty'
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '목록이 비어 있습니다' }),
  ).toBeVisible()
  expect(state.pages.slice(-2)).toEqual([1, 1])
})
test('미수집·만료 후보와 빈 사유는 연결 요청을 보내지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  state.candidate.sourceExpired = true
  await page.goto(path)
  await expect(
    page.getByRole('button', { name: '이 후보 선택', exact: true }),
  ).toBeDisabled()
  state.candidate.sourceExpired = false
  await page.reload()
  await page.getByRole('button', { name: '이 후보 선택', exact: true }).click()
  await page.getByRole('button', { name: '연결 확인', exact: true }).click()
  await expect(
    page.getByText('연결 사유를 입력해주세요.', { exact: true }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(0)
})
test('영상 종류·사유를 확인한 뒤에만 전송하고 처리 중 입력·이동을 잠근다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto(path)
  await select(page)
  await page.getByRole('button', { name: '공식 커버', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '음원', exact: true })
    .click()
  await page.getByRole('button', { name: '연결 확인', exact: true }).click()
  expect(state.registrations).toHaveLength(0)
  await page.getByRole('button', { name: '확인 후 저장', exact: true }).click()
  await expect(page.getByLabel('연결 사유*', { exact: true })).toBeDisabled()
  await page.getByRole('link', { name: '노래 목록', exact: true }).click()
  await expect(page).toHaveURL(path)
  await expect(
    page.getByRole('button', { name: '변경 버리고 이동', exact: true }),
  ).toHaveCount(0)
  expect(state.registrations).toHaveLength(1)
  release()
  await expect(
    page.getByRole('heading', { name: '영상을 연결했습니다' }),
  ).toBeVisible()
  expect(state.registrations[0]!.kind).toBe('AUDIO')
})
test('입력 오류 후 후보·영상 종류·사유를 보존한다', async ({ page }) => {
  const state = await setup(page)
  state.mode = 'invalid'
  await page.goto(path)
  await select(page)
  await confirm(page)
  await expect(
    page.getByText('연결 사유를 확인해주세요', { exact: true }),
  ).toBeVisible()
  await expect(page.getByLabel('연결 사유*', { exact: true })).toHaveValue(
    '공식 커버 영상 연결',
  )
  expect(state.editor.song.videos).toHaveLength(0)
})
test('버전 충돌은 최신 곡·후보를 비교하고 명시적으로 재확인한 뒤 저장한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'conflict'
  await page.goto(path)
  await select(page)
  await confirm(page)
  await expect(
    page.getByRole('heading', {
      name: '검토 중 곡 또는 후보가 변경되었습니다',
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '최신 저장값 조회', exact: true })
    .click()
  await expect(page.getByText(/곡 버전 6/)).toBeVisible()
  expect(state.registrations).toHaveLength(1)
  await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
  state.mode = 'success'
  await confirm(page)
  await expect(
    page.getByRole('heading', { name: '영상을 연결했습니다' }),
  ).toBeVisible()
  expect(state.registrations[1]).toMatchObject({ songVersion: 6, version: 4 })
})
for (const mode of ['lost', 'malformed'])
  test(`응답 유실·잘못된 응답(${mode})은 자동 재전송 없이 최신 연결을 확인한다`, async ({
    page,
  }) => {
    const state = await setup(page)
    state.mode = mode
    await page.goto(path)
    await select(page)
    await confirm(page)
    await expect(
      page.getByRole('heading', { name: '연결 결과를 확인해야 해요' }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: '연결 확인', exact: true }),
    ).toBeDisabled()
    await page
      .getByRole('button', { name: '최신 저장값 조회', exact: true })
      .click()
    await expect(
      page.getByText('선택한 영상이 이 곡에 연결되어 있습니다.'),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: '입력 재확인', exact: true }),
    ).toHaveCount(0)
    expect(state.registrations).toHaveLength(1)
    await expect(
      page.getByRole('heading', { name: '영상을 연결했습니다', exact: true }),
    ).toHaveCount(0)
  })
test('403은 권한 없음으로 표시하고 재전송하지 않는다', async ({ page }) => {
  const state = await setup(page)
  state.mode = 'denied'
  await page.goto(path)
  await select(page)
  await confirm(page)
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
})
test('미저장 입력에서 이동을 취소하면 유지하고 버리면 예정 목적지로 이동한다', async ({
  page,
}) => {
  await setup(page)
  await page.goto(path)
  await select(page)
  await page.getByRole('link', { name: '작업함', exact: true }).click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await expect(page.getByLabel('연결 사유*', { exact: true })).toHaveValue(
    '공식 커버 영상 연결',
  )
  await page.getByRole('link', { name: '작업함', exact: true }).click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin')
})
test('모바일·PC와 라이트·다크에서 영상 연결 폼이 화면을 넘치지 않는다', async ({
  page,
}, testInfo) => {
  await setup(page)
  await page.goto(path)
  await select(page)
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await page.evaluate(() => window.scrollTo(0, 0))
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: testInfo.outputPath(`video-${theme}.png`),
      fullPage: true,
      animations: 'disabled',
    })
  }
})

test('개별 후보 문맥의 영상 연결은 지정 후보를 미리 선택하고 입력할 수 있다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(
    `/admin/songs/${sid}/videos/new?candidateId=${rid}&returnTo=${encodeURIComponent('/admin/review-items?status=PENDING&page=1')}`,
  )
  await expect(
    page.getByText(/선택한 공식 영상 · 공식 채널/).first(),
  ).toBeVisible()
  await expect(page.getByLabel('연결 사유*', { exact: true })).toBeEnabled()
  expect(state.pages).toHaveLength(0)
})
