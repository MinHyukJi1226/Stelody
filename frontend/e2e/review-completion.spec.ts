import { test, expect, makeSong } from './fixtures'
import { mockAdmin } from './admin-fixtures'
import type { Page } from '@playwright/test'
import type {
  ReviewPreview,
  ReviewRequest,
} from '../src/features/admin/review-completion'

const id = (n: number) => makeSong(n).id
function preview(count = 21): ReviewPreview {
  return {
    items: Array.from({ length: count }, (_, i) => ({
      id: id(1000 + i),
      version: i + 2,
      youtubeId: `youtube${i}`,
      title: `검토 영상 ${i + 1}`,
      channelId: id(900),
      channelName: '아야츠노 유니',
      publishedAt: '2026-10-26T00:00:00Z',
      thumbnailUrl: null,
      durationSeconds: 180,
      observedAt: '2026-10-26T01:00:00Z',
      availability: 'PUBLIC',
      disposition: 'REVIEW',
      suggestedType: 'COVER',
      ruleVersion: '1',
      decisionReason: '검토 필요',
      status: 'PENDING',
      reviewNote: null,
      firstSeenAt: '2026-10-26T00:00:00Z',
      sourceExpired: false,
      registeredVideoId: null,
    })),
    channelMembers: { [id(900)]: id(901) },
    hasMore: false,
  }
}
async function setup(page: Page, count = 21) {
  const adminState = await mockAdmin(page)
  const state = {
    preview: preview(count),
    gets: 0,
    posts: [] as ReviewRequest[],
    mode: 'success',
    gate: Promise.resolve(),
    error: 0,
  }
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route('**/api/v1/admin/reviews/completion', async (route) => {
    if (route.request().method() === 'GET') {
      state.gets++
      return route.fulfill(
        state.error
          ? { status: state.error, json: { code: 'ACCESS_DENIED' } }
          : { json: state.preview },
      )
    }
    const request = route.request().postDataJSON() as ReviewRequest
    state.posts.push(request)
    expect(route.request().headers()['x-csrf-token']).toBe('test-csrf')
    await state.gate
    if (state.mode === 'lost') return route.abort()
    if (state.mode === 'invalid')
      return route.fulfill({
        status: 400,
        json: {
          code: 'INVALID_CATALOG_REQUEST',
          title: '등록 내용을 확인하세요',
          fieldErrors: [
            {
              field: 'selected[1].id',
              code: 'INVALID',
              message: '두 번째 후보를 확인해주세요',
            },
          ],
        },
      })
    if (state.mode === 'classification')
      return route.fulfill({
        status: 409,
        json: {
          code: 'CANDIDATE_CLASSIFICATION_REQUIRED',
          title: '자동 분류를 완료할 수 없습니다.',
          fieldErrors: [
            {
              field: 'selected[1].id',
              code: 'CONTENT_TYPE_CONFLICT',
              message: '유형 충돌',
            },
            {
              field: 'selected[0].id',
              code: 'TITLE_REQUIRES_EDIT',
              message: '제목 확인',
            },
          ],
        },
      })
    if (state.mode === 'conflict')
      return route.fulfill({
        status: 409,
        json: { code: 'REVIEW_VERSION_CONFLICT' },
      })
    if (state.mode === 'denied')
      return route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    return route.fulfill({
      json: {
        registeredSongs: request.selected.map((x, i) => ({
          reviewId: x.id,
          songId: id(2000 + i),
        })),
        deletedCount:
          state.mode === 'replay'
            ? 0
            : request.items.length - request.selected.length,
        replayed: state.mode === 'replay',
      },
    })
  })
  return { state, adminState }
}
async function selectCandidate(page: Page, title = '검토 영상 1') {
  await page.getByRole('checkbox', { name: title, exact: true }).check()
}
async function submit(page: Page, selected: number, deleted: number) {
  await page
    .getByRole('button', { name: '등록·정리 확인', exact: true })
    .click()
  await expect(page.getByRole('dialog')).toContainText(`삭제 예정 ${deleted}건`)
  const button = page.getByRole('button', {
    name: `${selected}곡 등록·미선택 ${deleted}건 영구 삭제`,
    exact: true,
  })
  await expect(button).toBeDisabled()
  await page
    .getByLabel('영구 삭제 범위를 확인했습니다', { exact: true })
    .check()
  await button.click()
}
test('페이지 간 선택과 최초 전체 대상을 유지하고 최종 확인 후에만 함께 처리한다', async ({
  page,
}) => {
  const { state } = await setup(page)
  await page.goto('/admin/reviews')
  await expect(
    page.getByRole('button', { name: '등록·정리 확인', exact: true }),
  ).toBeDisabled()
  await selectCandidate(page)
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await selectCandidate(page, '검토 영상 21')
  await expect(page.getByText('선택 2건', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '이전', exact: true }).click()
  await expect(
    page.getByRole('checkbox', { name: '검토 영상 1', exact: true }),
  ).toBeChecked()
  expect(state.gets).toBe(1)
  expect(state.posts).toHaveLength(0)
  await submit(page, 2, 19)
  await expect(
    page.getByRole('heading', { name: '등록·정리를 완료했어요' }),
  ).toBeVisible()
  const request = state.posts[0]!
  expect(request.items).toHaveLength(21)
  expect(request.items[20]).toEqual({ id: id(1020), version: 22 })
  expect(request.selected.map((x) => x.id)).toEqual([id(1000), id(1020)])
  expect(request.selected).toEqual([{ id: id(1000) }, { id: id(1020) }])
})
test('후보를 선택하면 별도 정보 입력 없이 최종 확인창으로 이동한다', async ({
  page,
}) => {
  const { state } = await setup(page, 1)
  await page.goto('/admin/reviews')
  const button = page.getByRole('button', {
    name: '등록·정리 확인',
    exact: true,
  })
  await expect(button).toBeDisabled()
  await selectCandidate(page)
  await expect(button).toBeEnabled()
  await expect(
    page.getByRole('button', { name: '등록 정보 확인', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(0)
  await button.click()
  await expect(page.getByRole('dialog')).toContainText(
    '모든 곡은 초안으로 저장됩니다.',
  )
  expect(state.posts).toHaveLength(0)
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await page
    .getByRole('checkbox', { name: '검토 영상 1', exact: true })
    .uncheck()
  await expect(button).toBeDisabled()
})
test('커버 표기가 없는 미분류 후보도 ID만 전달하고 서버 등록 결과를 표시한다', async ({
  page,
}) => {
  const { state } = await setup(page, 1)
  const title = 'IA - オツキミリサイタル 달맞이 리사이틀 / 네네코 마시로 (4K)'
  state.preview.items[0]!.title = title
  state.preview.items[0]!.suggestedType = 'UNKNOWN'
  await page.goto('/admin/reviews')
  await selectCandidate(page, title)
  await page
    .getByRole('button', { name: '등록·정리 확인', exact: true })
    .click()
  await expect(page.getByRole('dialog')).toContainText(
    '유형이 미확정인 후보는 기본 커버로 등록합니다.',
  )
  await expect(page.getByRole('dialog')).toContainText(title)
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await submit(page, 1, 0)
  await expect(
    page.getByRole('heading', { name: '등록·정리를 완료했어요' }),
  ).toBeVisible()
  expect(state.posts[0]!.selected).toEqual([{ id: id(1000) }])
  await expect(
    page.getByText('곡은 초안으로 등록됩니다.', { exact: false }),
  ).toBeVisible()
})
test('응답 유실 후에는 동일 UUID와 전체 본문을 그대로 재전송한다', async ({
  page,
}) => {
  const { state } = await setup(page, 2)
  state.mode = 'lost'
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await submit(page, 1, 1)
  await expect(
    page.getByRole('heading', { name: '처리 결과를 확인해야 해요' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '등록·정리 확인', exact: true }),
  ).toHaveCount(0)
  state.mode = 'replay'
  await page
    .getByRole('button', { name: '같은 요청 결과 확인', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '이전 요청의 완료를 확인했어요' }),
  ).toBeVisible()
  expect(state.posts).toHaveLength(2)
  expect(state.posts[1]).toEqual(state.posts[0])
  await expect(
    page.getByText(
      '최초 처리의 삭제 건수는 이번 응답에서 다시 제공되지 않습니다.',
    ),
  ).toBeVisible()
})
test('후보 변경 충돌 후에는 자동 실행하지 않고 목록을 다시 확인한다', async ({
  page,
}) => {
  const { state } = await setup(page, 2)
  state.mode = 'conflict'
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await submit(page, 1, 1)
  await expect(
    page.getByRole('heading', { name: '검토 대상이 변경됐어요' }),
  ).toBeVisible()
  state.preview = preview(3)
  await page
    .getByRole('button', { name: '목록 다시 조회', exact: true })
    .click()
  await expect(
    page.getByText('이번 검토 3건 · 선택 0건 · 미선택 3건'),
  ).toBeVisible()
  expect(state.posts).toHaveLength(1)
})
test('입력 오류를 해당 후보에 표시하고 선택을 유지한다', async ({ page }) => {
  const { state } = await setup(page, 2)
  state.mode = 'invalid'
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await selectCandidate(page, '검토 영상 2')
  await submit(page, 2, 0)
  const row = page.locator('article').filter({
    has: page.getByRole('checkbox', { name: '검토 영상 2', exact: true }),
  })
  await expect(row).toContainText('두 번째 후보를 확인해주세요')
  await expect(
    page.getByRole('checkbox', { name: '검토 영상 1', exact: true }),
  ).toBeChecked()
  await expect(
    page.getByRole('checkbox', { name: '검토 영상 2', exact: true }),
  ).toBeChecked()
  expect(state.posts).toHaveLength(1)
})
test('유형 충돌로 전체 취소되면 후보별 사유를 표시하고 자동 재시도하지 않는다', async ({
  page,
}) => {
  const { state } = await setup(page, 2)
  state.mode = 'classification'
  await page.goto('/admin/reviews')
  await selectCandidate(page, '검토 영상 2')
  await selectCandidate(page)
  await submit(page, 2, 0)
  await expect(
    page.getByRole('heading', {
      name: '등록 정보를 자동으로 구성하지 못했어요',
    }),
  ).toBeVisible()
  await expect(
    page.getByRole('alert').filter({ hasText: '검토 영상 2' }),
  ).toContainText('기존 곡 유형 제안과 현재 분류가 일치하지 않습니다.')
  await expect(
    page.getByRole('alert').filter({ hasText: '검토 영상 1' }),
  ).toContainText('영상 제목으로 등록할 곡명을 구성할 수 없습니다.')
  await expect(
    page.getByRole('button', { name: '같은 요청 결과 확인', exact: true }),
  ).toHaveCount(0)
  expect(state.posts).toHaveLength(1)
  await page
    .getByRole('button', { name: '목록 다시 조회', exact: true })
    .click()
  await expect(
    page.getByText('이번 검토 2건 · 선택 0건 · 미선택 2건'),
  ).toBeVisible()
  expect(state.posts).toHaveLength(1)
})
test('처리 중 중복 실행과 화면 이동을 막는다', async ({ page }) => {
  const { state } = await setup(page, 1)
  let release!: () => void
  state.gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await submit(page, 1, 0)
  await expect(
    page.getByText('완료될 때까지 입력·이동·중복 실행을 기다려주세요.'),
  ).toBeVisible()
  await page
    .getByRole('navigation', { name: '관리자 메뉴' })
    .getByRole('link', { name: '작업함', exact: true })
    .click()
  await expect(page).toHaveURL('/admin/reviews')
  expect(state.posts).toHaveLength(1)
  release()
  await expect(
    page.getByRole('heading', { name: '등록·정리를 완료했어요' }),
  ).toBeVisible()
})
test('선택을 버리는 이탈에는 확인창을 사용하고 취소 시 선택을 보존한다', async ({
  page,
}) => {
  await setup(page, 1)
  await page.goto('/admin/reviews')
  await page.getByRole('checkbox', { name: '검토 영상 1', exact: true }).check()
  await page
    .getByRole('navigation', { name: '관리자 메뉴' })
    .getByRole('link', { name: '노래 목록', exact: true })
    .click()
  await page.getByRole('button', { name: '계속 검토', exact: true }).click()
  await expect(
    page.getByRole('checkbox', { name: '검토 영상 1', exact: true }),
  ).toBeChecked()
  await page
    .getByRole('navigation', { name: '관리자 메뉴' })
    .getByRole('link', { name: '노래 목록', exact: true })
    .click()
  await page
    .getByRole('button', { name: '검토 종료하고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin/songs')
})
test('hasMore와 빈 목록을 구분하며 100개 초과 선택을 전송하지 않는다', async ({
  page,
}) => {
  const { state } = await setup(page, 101)
  state.preview.hasMore = true
  await page.goto('/admin/reviews')
  await expect(
    page.getByText(
      '추가 후보는 이번 삭제 대상이 아니며 다음 검토에서 처리합니다.',
      { exact: false },
    ),
  ).toBeVisible()
  for (let i = 0; i < 5; i++) {
    await page
      .getByRole('button', { name: '현재 페이지 선택', exact: true })
      .click()
    await page.getByRole('button', { name: '다음', exact: true }).click()
  }
  await page
    .getByRole('checkbox', { name: '검토 영상 101', exact: true })
    .click()
  await expect(
    page.getByText('등록할 후보는 최대 100개까지 선택할 수 있습니다.'),
  ).toBeVisible()
  await expect(
    page.getByRole('checkbox', { name: '검토 영상 101', exact: true }),
  ).not.toBeChecked()
  expect(state.posts).toHaveLength(0)
})
test('조회 오류와 권한 오류를 0건 또는 완료로 바꾸지 않는다', async ({
  page,
}) => {
  const { state } = await setup(page)
  state.error = 503
  await page.goto('/admin/reviews')
  await expect(
    page.getByRole('heading', { name: '검토 대상을 불러오지 못했어요' }),
  ).toBeVisible()
  await expect(page.getByText('이번 검토 0건', { exact: false })).toHaveCount(0)
  state.error = 403
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
})
test('선택·확인창은 양쪽 테마에서 가로로 넘치지 않는다', async ({
  page,
  isMobile,
}, info) => {
  await setup(page, 2)
  await page.setViewportSize(
    isMobile ? { width: 390, height: 844 } : { width: 1440, height: 1024 },
  )
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  for (const theme of ['light', 'dark']) {
    if ((await page.locator('html').getAttribute('data-theme')) !== theme)
      await page
        .getByRole('button', {
          name: theme === 'dark' ? '다크 모드로 변경' : '라이트 모드로 변경',
        })
        .click()
    await page.evaluate(() => (document.activeElement as HTMLElement)?.blur())
    await page.screenshot({
      path: info.outputPath(`selection-${theme}.png`),
      fullPage: true,
    })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page
      .getByRole('button', { name: '등록·정리 확인', exact: true })
      .click()
    await page.evaluate(() => (document.activeElement as HTMLElement)?.blur())
    await page.screenshot({
      path: info.outputPath(`confirm-${theme}.png`),
      fullPage: true,
    })
    expect(
      await page
        .getByRole('dialog')
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true)
    await page.getByRole('button', { name: '취소', exact: true }).click()
  }
})

test('응답 유실 뒤 새로고침·작업함 방문에도 같은 요청을 복구한다', async ({
  page,
}) => {
  const { state } = await setup(page, 2)
  state.mode = 'lost'
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await submit(page, 1, 1)
  await expect(
    page.getByRole('heading', { name: '처리 결과를 확인해야 해요' }),
  ).toBeVisible()
  const original = state.posts[0]!
  state.preview = preview(5)
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '처리 결과를 확인해야 해요' }),
  ).toBeVisible()
  expect(state.gets).toBe(1)
  await page.getByRole('button', { name: '작업함으로', exact: true }).click()
  await page
    .getByRole('button', { name: '검토 종료하고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin')
  await page
    .getByRole('link', { name: '목록 보기', exact: true })
    .first()
    .click()
  await expect(
    page.getByRole('heading', { name: '처리 결과를 확인해야 해요' }),
  ).toBeVisible()
  state.mode = 'replay'
  await page
    .getByRole('button', { name: '같은 요청 결과 확인', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '이전 요청의 완료를 확인했어요' }),
  ).toBeVisible()
  expect(state.posts[1]).toEqual(original)
  expect(state.gets).toBe(1)
})

test('요청 보관이 차단되면 전송 전에 안내한다', async ({ page }) => {
  const { state } = await setup(page, 1)
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (key.startsWith('stelody-review-completion:'))
        throw new Error('storage blocked')
      return original.call(this, key, value)
    }
  })
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  await submit(page, 1, 0)
  await expect(page.getByRole('alert')).toContainText('전송하지 않았습니다')
  expect(state.posts).toHaveLength(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('세션 조회의 일시적 실패에도 검토 선택을 유지하고 복구 전 전송을 막는다', async ({
  page,
}) => {
  const { state, adminState } = await setup(page, 1)
  await page.goto('/admin/reviews')
  await selectCandidate(page)
  adminState.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  // TanStack Query observes visibility changes for focus refetch.
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    })
    window.dispatchEvent(new Event('visibilitychange'))
  })
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  expect(state.posts).toHaveLength(0)
  adminState.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByText('선택 1건', { exact: true })).toBeVisible()
  await submit(page, 1, 0)
  await expect(
    page.getByRole('heading', { name: '등록·정리를 완료했어요' }),
  ).toBeVisible()
})
