import { test, expect, makeSong } from './fixtures'
import { mockAdmin } from './admin-fixtures'
import { makeAdminSong, sid } from '../src/test/admin-song'
import type { Page } from '@playwright/test'
const id = makeSong(970).id,
  path = `/admin/review-items/${id}`
async function setup(page: Page) {
  const admin = await mockAdmin(page)
  const state = {
    admin,
    item: {
      id,
      version: 4,
      youtubeId: 'abcdefghijk',
      channelId: null,
      channelName: '공식 채널',
      title: '후보 영상',
      publishedAt: '2026-10-20T09:00:00Z',
      thumbnailUrl: null,
      durationSeconds: null,
      observedAt: '2026-10-31T00:00:00Z',
      availability: 'PUBLIC',
      disposition: 'REVIEW',
      suggestedType: 'COVER',
      ruleVersion: 'title-v2:5',
      decisionReason: 'COVER_REQUIRES_PARTICIPANT_REVIEW',
      status: 'PENDING',
      reviewNote: null as string | null,
      firstSeenAt: '2026-10-30T00:00:00Z',
      sourceExpired: false,
      registeredVideoId: null as string | null,
      registeredSongId: null as string | null,
    },
    error: 0,
    mode: 'success',
    writes: [] as unknown[],
  }
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route(`**/api/v1/admin/reviews/${id}`, async (r) => {
    if (r.request().method() === 'GET')
      return r.fulfill(
        state.error
          ? { status: state.error, json: { code: 'UNAVAILABLE' } }
          : { json: state.item },
      )
    expect(r.request().headers()['x-csrf-token']).toBe('test-csrf')
    const b = r.request().postDataJSON()
    state.writes.push(b)
    if (state.mode === 'conflict')
      return r.fulfill({
        status: 409,
        json: { code: 'REVIEW_VERSION_CONFLICT' },
      })
    if (state.mode === 'registered')
      return r.fulfill({
        status: 409,
        json: { code: 'REVIEW_ALREADY_REGISTERED' },
      })
    if (state.mode === 'denied')
      return r.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    state.item = {
      ...state.item,
      status: b.status,
      reviewNote: b.reason,
      version: b.version + 1,
    }
    if (state.mode === 'lost') return r.abort('failed')
    return r.fulfill({ json: state.item })
  })
  await page.route('**/api/v1/admin/reviews?*', (r) => {
    const u = new URL(r.request().url())
    return r.fulfill({
      json: {
        page: Number(u.searchParams.get('page')),
        size: Number(u.searchParams.get('size')),
        hasNext: Number(u.searchParams.get('page')) === 0,
        items:
          (u.searchParams.get('status') === 'ALL' ||
            state.item.status === u.searchParams.get('status')) &&
          (!u.searchParams.get('disposition') ||
            state.item.disposition === u.searchParams.get('disposition'))
            ? [state.item]
            : [],
      },
    })
  })
  return state
}
async function fill(page: Page) {
  await page
    .getByRole('button', { name: '검토 상태 선택', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '제외', exact: true })
    .click()
  await page
    .getByLabel('변경 사유*', { exact: true })
    .fill('노래 전체 영상 아님')
}
async function save(page: Page) {
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
}
test('수집 원본과 검토 상태를 구분하고 새 사유가 없으면 저장을 막는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.item.sourceExpired = true
  await page.goto(path)
  await expect(
    page.getByRole('heading', {
      name: '수집 근거가 오래되었습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 저장', exact: true }),
  ).toBeEnabled()
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(s.writes).toHaveLength(0)
})
for (const mode of ['conflict', 'registered'])
  test(`${mode} 충돌은 최신 값 비교 뒤 명시적으로 재확인한다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.mode = mode
    await page.goto(path)
    await fill(page)
    await save(page)
    await expect(
      page.getByRole('heading', {
        name: '검토 중 후보가 변경되었습니다',
        exact: true,
      }),
    ).toBeVisible()
    s.item.version = 5
    s.item.status = mode === 'registered' ? 'REGISTERED' : 'IGNORED'
    await page
      .getByRole('button', { name: '최신 저장값 조회', exact: true })
      .click()
    await expect(
      page.getByRole('button', { name: '입력 재확인', exact: true }),
    ).toBeVisible()
    await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
    if (mode === 'registered') {
      await expect(
        page.getByRole('heading', {
          name: '이미 등록된 후보입니다',
          exact: true,
        }),
      ).toBeVisible()
      await expect(
        page.getByRole('button', { name: '변경 저장', exact: true }),
      ).toHaveCount(0)
    } else
      await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
        '노래 전체 영상 아님',
      )
    expect(s.writes).toEqual([
      { version: 4, status: 'IGNORED', reason: '노래 전체 영상 아님' },
    ])
  })
test('등록 완료 후보는 검토 상태를 바꾸지 않는다', async ({ page }) => {
  const s = await setup(page)
  s.item.status = 'REGISTERED'
  s.item.registeredVideoId = makeSong(971).id
  await page.goto(path)
  await expect(
    page.getByRole('heading', { name: '이미 등록된 후보입니다', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 저장', exact: true }),
  ).toHaveCount(0)
  expect(s.writes).toHaveLength(0)
})
test('응답 유실은 같은 최신 값이어도 성공으로 추정하거나 재전송하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.mode = 'lost'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '최신 저장값 조회', exact: true })
    .click()
  await expect(
    page.getByRole('heading', {
      name: '최신 내용을 확인했습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 저장', exact: true }),
  ).toBeDisabled()
  expect(s.writes).toHaveLength(1)
})
test('권한 오류 후에도 미저장 이동 확인을 사용할 수 있다', async ({ page }) => {
  const s = await setup(page)
  s.mode = 'denied'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('main')
    .getByRole('link', { name: '홈으로', exact: true })
    .click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await page
    .getByRole('main')
    .getByRole('link', { name: '홈으로', exact: true })
    .click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/')
})
for (const status of [401, 403, 404, 503])
  test(`후보 조회 ${status} 분리`, async ({ page }) => {
    const s = await setup(page)
    s.error = status
    await page.goto(path)
    await expect(
      page.getByRole('heading', {
        name:
          status === 401
            ? '로그인이 필요해요'
            : status === 403
              ? '관리자 권한이 없어요'
              : status === 404
                ? '검토 후보를 찾을 수 없습니다'
                : '후보 정보를 조회하지 못했습니다',
        exact: true,
      }),
    ).toBeVisible()
    expect(s.writes).toHaveLength(0)
  })
test('PC·모바일 라이트·다크 후보 상세가 화면 너비를 넘지 않는다', async ({
  page,
}, info) => {
  await setup(page)
  await page.goto(path)
  for (const theme of ['light', 'dark']) {
    await page.goto(path)
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await expect(page.getByLabel('변경 사유*', { exact: true })).toBeVisible()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: info.outputPath(`review-${theme}.png`),
      fullPage: true,
    })
    await page.goto('/admin/review-items')
    await expect(
      page.getByRole('link', { name: '후보 상세', exact: true }),
    ).toBeVisible()
    await page.screenshot({
      path: info.outputPath(`review-list-${theme}.png`),
      fullPage: true,
    })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
  }
})

test('메뉴·상태별 목록·페이지에서 상세를 열고 제외 저장 후 원래 문맥으로 돌아온다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '개별 후보 검토', exact: true }).click()
  await expect(
    page.getByRole('button', { name: '전체', exact: true }).first(),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '검토 대기', exact: true }).click()
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await page.getByRole('link', { name: '후보 상세', exact: true }).click()
  await fill(page)
  await save(page)
  await expect(page).toHaveURL(
    '/admin/review-items?status=PENDING&disposition=&page=1&size=20',
  )
  await expect(
    page.getByRole('heading', { name: '목록이 비어 있습니다', exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '제외', exact: true }).click()
  await page.getByRole('link', { name: '후보 상세', exact: true }).click()
  await page.reload()
  await expect(
    page.getByRole('button', { name: '검토 상태 선택', exact: true }),
  ).toHaveText('제외')
  expect(s.writes).toHaveLength(1)
})

test('전체 조회는 상태가 섞인 목록과 분류·페이지 문맥을 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  const queries: URL[] = []
  await page.route('**/api/v1/admin/reviews?*', (r) => {
    const u = new URL(r.request().url())
    queries.push(u)
    return r.fulfill({
      json: {
        page: Number(u.searchParams.get('page')),
        size: 10,
        hasNext: true,
        items: [
          state.item,
          { ...state.item, id: makeSong(972).id, status: 'IGNORED' },
          {
            ...state.item,
            id: makeSong(973).id,
            status: 'REGISTERED',
            registeredSongId: sid,
          },
        ],
      },
    })
  })
  await page.goto(
    '/admin/review-items?status=ALL&disposition=REVIEW&page=2&size=10',
  )
  await expect(
    page.getByRole('link', { name: '후보 상세', exact: true }),
  ).toHaveCount(3)
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page).toHaveURL(
    '/admin/review-items?status=ALL&disposition=REVIEW&page=3&size=10',
  )
  await expect(
    page.getByRole('link', { name: '후보 상세', exact: true }),
  ).toHaveCount(3)
  expect(queries.at(-1)?.searchParams.get('status')).toBe('ALL')
  expect(queries.at(-1)?.searchParams.get('disposition')).toBe('REVIEW')
  await page
    .getByRole('link', { name: '후보 상세', exact: true })
    .first()
    .click()
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL(
    '/admin/review-items?status=ALL&disposition=REVIEW&page=3&size=10',
  )
})

test('등록 후보는 영상 ID가 아닌 현재 연결 곡으로 이동하고 목록 문맥을 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.item.status = 'REGISTERED'
  state.item.registeredVideoId = makeSong(971).id
  state.item.registeredSongId = sid
  await page.route(`**/api/v1/admin/songs/${sid}`, (r) =>
    r.fulfill({ json: makeAdminSong() }),
  )
  const returnTo =
    '/admin/review-items?status=ALL&disposition=REVIEW&page=2&size=10'
  await page.goto(path + '?' + new URLSearchParams({ returnTo }))
  await page.getByRole('button', { name: '기존 곡 수정', exact: true }).click()
  await expect(page).toHaveURL(
    `/admin/songs/${sid}/edit?${new URLSearchParams({ returnTo })}`,
  )
  await expect(
    page.getByRole('heading', { name: '기존 곡 수정', exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(0)
})

test('등록 곡 연결이 없으면 영상 ID로 곡 이동을 추측하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  state.item.status = 'REGISTERED'
  state.item.registeredVideoId = makeSong(971).id
  await page.goto(path)
  await expect(
    page.getByRole('button', { name: '기존 곡 수정', exact: true }),
  ).toBeDisabled()
})
