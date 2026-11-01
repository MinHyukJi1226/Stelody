import { fixture as artistFixture } from './original-artist-fixtures'
import { test, expect, makeSong } from './fixtures'
import { mockSongEditor, editPath, sid } from './admin-song-fixtures'
import type { Page } from '@playwright/test'
const auditId = makeSong(950).id
async function setup(page: Page) {
  const editor = await mockSongEditor(page)
  const state = {
    editor,
    error: 0,
    empty: false,
    calls: [] as URL[],
    gets: 0,
    before: {
      title: '기존 곡명',
      aliases: ['old'],
      flag: false,
      remove: 1,
    } as unknown,
    after: {
      title: '새 곡명',
      aliases: Array.from({ length: 30 }, (_, i) => 'alias-' + i),
      flag: true,
      add: null,
    } as unknown,
    actor: makeSong(991).id as string | null,
  }
  await page.route('**/api/v1/admin/**/audit?*', (r) => {
    const u = new URL(r.request().url())
    state.calls.push(u)
    state.gets++
    if (state.error)
      return r.fulfill({
        status: state.error,
        json: {
          code:
            state.error === 404
              ? 'CATALOG_NOT_FOUND'
              : state.error === 401
                ? 'UNAUTHENTICATED'
                : state.error === 403
                  ? 'ACCESS_DENIED'
                  : 'UNAVAILABLE',
        },
      })
    const parts = u.pathname.split('/'),
      kind =
        parts.at(-2) === 'collection-rules' ? 'collection-rules' : parts.at(-3),
      id = kind === 'collection-rules' ? makeSong(900).id : parts.at(-2)
    return r.fulfill({
      json: {
        page: Number(u.searchParams.get('page')),
        size: Number(u.searchParams.get('size')),
        hasNext: Number(u.searchParams.get('page')) === 0,
        items: state.empty
          ? []
          : [
              {
                id: auditId,
                targetType:
                  kind === 'collection-rules'
                    ? 'COLLECTION_RULES'
                    : kind!.toUpperCase(),
                targetId: id,
                actorId: state.actor,
                action: state.before === null ? 'CREATE' : 'UPDATE',
                before: state.before,
                after: state.after,
                reason: '공식 크레딧 확인',
                changedAt: '2026-11-01T00:00:00Z',
              },
            ],
      },
    })
  })
  return state
}
async function direct(page: Page) {
  await page.goto(editPath)
  await page.getByRole('button', { name: '변경 이력', exact: true }).click()
  await expect(
    page
      .getByRole('dialog')
      .getByRole('heading', { name: '변경 이력', exact: true }),
  ).toBeVisible()
}
async function openHub(page: Page) {
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '변경 이력', exact: true })
    .click()
}
async function kind(page: Page, label: string) {
  await page
    .getByRole('dialog')
    .last()
    .getByRole('heading', { name: label, exact: true })
    .locator('..')
    .getByRole('button')
    .click()
}
test('곡 편집 초안과 URL을 보존하고 페이지·상세·긴 배열을 읽기 전용으로 조회한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(editPath)
  await page.getByLabel('곡명*', { exact: true }).fill('유지할 초안')
  await page.getByRole('button', { name: '변경 이력', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '작성 중인 입력이 있습니다',
      exact: true,
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '입력을 유지하고 이력 열기', exact: true })
    .click()
  const overlay = page.getByRole('dialog').last()
  await overlay.getByRole('button', { name: '다음', exact: true }).click()
  await expect(overlay.getByText('2페이지', { exact: true })).toBeVisible()
  await overlay
    .getByRole('button', { name: '변경 전후 보기', exact: true })
    .click()
  await expect(
    overlay.getByRole('heading', { name: '변경 전후 상세', exact: true }),
  ).toBeVisible()
  await overlay
    .getByRole('button', { name: '긴 배열 펼치기', exact: true })
    .click()
  await expect(overlay.getByText(/alias-29/)).toBeVisible()
  await overlay
    .getByRole('button', { name: '상세로 돌아가기', exact: true })
    .click()
  await overlay
    .getByRole('button', { name: '이력 목록으로', exact: true })
    .first()
    .click()
  await expect(overlay.getByText('2페이지', { exact: true })).toBeVisible()
  await overlay
    .getByRole('button', { name: '원래 화면으로', exact: true })
    .click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '유지할 초안',
  )
  await expect(page).toHaveURL(editPath)
  expect(s.editor.puts).toHaveLength(0)
})
test('관리 메뉴에서 검색·페이지·선택 확인을 거쳐 곡 이력에 진입한다', async ({
  page,
}) => {
  const s = await setup(page)
  await openHub(page)
  await kind(page, '곡')
  const overlay = page.getByRole('dialog').last()
  await overlay.getByLabel('곡 제목·검색 별칭', { exact: true }).fill('노래')
  await overlay.getByRole('button', { name: '검색', exact: true }).click()
  await overlay.getByRole('button', { name: '다음', exact: true }).click()
  await overlay
    .getByRole('button', { name: '이 대상 선택', exact: true })
    .first()
    .click()
  await overlay
    .getByRole('button', { name: '선택 목록으로', exact: true })
    .last()
    .click()
  await expect(
    overlay.getByLabel('곡 제목·검색 별칭', { exact: true }),
  ).toHaveValue('노래')
  await expect(overlay.getByText('2페이지', { exact: true })).toBeVisible()
  await overlay
    .getByRole('button', { name: '이 대상 선택', exact: true })
    .first()
    .click()
  await overlay.getByRole('button', { name: '이력 보기', exact: true }).click()
  await expect(
    overlay.getByRole('button', { name: '변경 전후 보기', exact: true }),
  ).toBeVisible()
  expect(s.calls.at(-1)?.pathname).toContain('/songs/')
})
for (const label of [
  '원곡',
  '멤버',
  '공식 채널',
  '개별 검토 후보',
  '수집 분류 규칙',
])
  test(`관리 메뉴의 ${label} 선택 경로`, async ({ page }) => {
    const s = await setup(page)
    // Use contract-shaped list results without creating or updating records.
    await page.route('**/api/v1/admin/*?*', (r) => {
      const u = new URL(r.request().url())
      if (u.pathname.endsWith('/audit')) return r.fallback()
      return r.fulfill({
        json: {
          page: Number(u.searchParams.get('page')),
          size: 20,
          hasNext: false,
          items: [{ id: sid, name: label + ' 대상', title: label + ' 대상' }],
        },
      })
    })
    await openHub(page)
    await kind(page, label)
    const overlay = page.getByRole('dialog').last()
    if (label !== '수집 분류 규칙')
      await overlay
        .getByRole('button', { name: '이 대상 선택', exact: true })
        .click()
    await overlay
      .getByRole('button', { name: '이력 보기', exact: true })
      .click()
    await expect(
      overlay.getByRole('button', { name: '변경 전후 보기', exact: true }),
    ).toBeVisible()
    expect(s.gets).toBe(1)
  })
for (const label of ['아티스트', '영상'])
  test(`${label}는 소유 곡을 고른 뒤 연결 변경 없이 대상만 조회한다`, async ({
    page,
  }) => {
    const s = await setup(page)
    await openHub(page)
    await kind(page, label)
    const overlay = page.getByRole('dialog').last()
    if (label === '아티스트')
      await overlay
        .getByRole('button', { name: '곡에서 찾기', exact: true })
        .click()
    await overlay
      .getByRole('button', { name: '이 대상 선택', exact: true })
      .first()
      .click()
    if (label === '아티스트')
      await page.route('**/api/v1/admin/artists?*', (r) =>
        r.fulfill({
          json: {
            page: 0,
            size: 20,
            hasNext: false,
            items: [{ id: makeSong(930).id, name: '원곡자' }],
          },
        }),
      )
    await overlay
      .getByRole('button', { name: '이 대상 선택', exact: true })
      .first()
      .click()
    await overlay
      .getByRole('button', { name: '이력 보기', exact: true })
      .click()
    await expect(
      overlay.getByRole('button', { name: '변경 전후 보기', exact: true }),
    ).toBeVisible()
    expect(s.editor.puts).toHaveLength(0)
  })
for (const status of [404, 503, 401, 403])
  test(`이력 ${status}를 빈 이력과 구분한다`, async ({ page }) => {
    const s = await setup(page)
    s.error = status
    await page.goto(editPath)
    await page.getByRole('button', { name: '변경 이력', exact: true }).click()
    await expect(
      page.getByRole('heading', {
        name:
          status === 404
            ? '대상을 찾을 수 없습니다'
            : status === 503
              ? '변경 이력을 조회하지 못했습니다'
              : status === 401
                ? '로그인이 필요해요'
                : '관리자 권한이 없어요',
        exact: true,
      }),
    ).toBeVisible()
    if (status === 503) {
      s.error = 0
      await page.getByRole('button', { name: '다시 조회', exact: true }).click()
      await expect(
        page.getByRole('button', { name: '변경 전후 보기', exact: true }),
      ).toBeVisible()
    }
  })
test('생성 전 null과 불리언·숫자·문자열을 실제 값으로 표시한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.before = null
  s.after = { zero: 0, no: false, html: '<script>alert(1)</script>' }
  s.actor = null
  await direct(page)
  await page
    .getByRole('button', { name: '변경 전후 보기', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '변경 전 정보 없음', exact: true }),
  ).toBeVisible()
  await expect(page.getByText(/<script>alert\(1\)<\/script>/)).toBeVisible()
  await expect(
    page.getByText('작업: CREATE · 관리자 ID 기록 없음', { exact: true }),
  ).toBeVisible()
})
test('존재하는 대상의 빈 이력을 표시한다', async ({ page }) => {
  const s = await setup(page)
  s.empty = true
  await direct(page)
  await expect(
    page.getByRole('heading', { name: '변경 이력이 없습니다', exact: true }),
  ).toBeVisible()
})
test('PC·모바일 양쪽 테마에서 대상 선택·이력·비교가 화면을 넘지 않는다', async ({
  page,
}, info) => {
  await setup(page)
  await page.goto(editPath)
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await page.getByRole('button', { name: '변경 이력', exact: true }).click()
    const overlay = page.getByRole('dialog').last()
    await expect(
      overlay.getByRole('button', { name: '변경 전후 보기', exact: true }),
    ).toBeVisible()
    await page.screenshot({ path: info.outputPath(`audit-list-${theme}.png`) })
    await overlay
      .getByRole('button', { name: '변경 전후 보기', exact: true })
      .click()
    await overlay
      .getByRole('button', { name: '긴 배열 펼치기', exact: true })
      .click()
    expect(await overlay.evaluate((e) => e.scrollWidth <= e.clientWidth)).toBe(
      true,
    )
    await page.screenshot({
      path: info.outputPath(`audit-detail-${theme}.png`),
    })
    await overlay
      .getByRole('button', { name: '상세로 돌아가기', exact: true })
      .click()
    await overlay
      .getByRole('button', { name: '이력 목록으로', exact: true })
      .first()
      .click()
    await overlay
      .getByRole('button', { name: '원래 화면으로', exact: true })
      .click()
    await openHub(page)
    await page.screenshot({ path: info.outputPath(`audit-hub-${theme}.png`) })
    await kind(page, '곡')
    const picker = page.getByRole('dialog').last()
    await expect(
      picker.getByRole('button', { name: '이 대상 선택', exact: true }).first(),
    ).toBeVisible()
    expect(await picker.evaluate((e) => e.scrollWidth <= e.clientWidth)).toBe(
      true,
    )
    await page.screenshot({
      path: info.outputPath(`audit-picker-${theme}.png`),
    })
    await picker
      .getByRole('button', { name: '대상 종류로', exact: true })
      .click()
    await page
      .getByRole('dialog')
      .last()
      .getByRole('button', { name: '관리 화면으로', exact: true })
      .click()
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '닫기', exact: true })
      .click()
    await page.goto(editPath)
  }
})

test('새로고침 후에도 같은 대상·이력 페이지를 복구한다', async ({ page }) => {
  await setup(page)
  await direct(page)
  const overlay = page.getByRole('dialog').last()
  await overlay.getByRole('button', { name: '다음', exact: true }).click()
  await expect(overlay.getByText('2페이지', { exact: true })).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('dialog').last().getByText('2페이지', { exact: true }),
  ).toBeVisible()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '원래 화면으로', exact: true })
    .click()
  await expect(page).toHaveURL(editPath)
})

test('영상 선택창에서 이력을 열어도 미적용 대표 영상 선택을 유지한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(editPath)
  await page
    .getByRole('button', { name: '대표 영상 선택', exact: true })
    .click()
  const selector = page.getByRole('dialog').last()
  await selector.getByRole('radio').first().check()
  await selector
    .getByRole('button', { name: '영상 변경 이력', exact: true })
    .click()
  await page
    .getByRole('button', { name: '입력을 유지하고 이력 열기', exact: true })
    .click()
  await expect(
    page
      .getByRole('dialog')
      .last()
      .getByRole('button', { name: '변경 전후 보기', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '원래 화면으로', exact: true })
    .click()
  await expect(
    page.getByRole('dialog').last().getByRole('radio').first(),
  ).toBeChecked()
  expect(s.editor.puts).toHaveLength(0)
})

test('저장 응답 유실 중 이력 조회는 성공을 추정하거나 입력을 없애지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.editor.mode = 'lost'
  await page.goto(editPath)
  await page.getByLabel('곡명*', { exact: true }).fill('불확실한 초안')
  await page.getByLabel('변경 사유*', { exact: true }).fill('근거 확인')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page.getByText('저장 결과를 확인해야 해요', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '변경 이력', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과가 불확실합니다',
      exact: true,
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '입력을 유지하고 이력 열기', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '원래 화면으로', exact: true })
    .click()
  await expect(
    page.getByText('저장 결과를 확인해야 해요', { exact: true }),
  ).toBeVisible()
  expect(s.editor.puts).toHaveLength(1)
})

test('아티스트 편집창의 미저장 이름은 이력 닫기 후 같은 창에 남는다', async ({
  page,
}) => {
  await setup(page)
  const s = await artistFixture(page)
  await page.route('**/api/v1/admin/artists/*/audit?*', (r) => {
    const u = new URL(r.request().url()),
      id = u.pathname.split('/').at(-2)
    return r.fulfill({
      json: {
        page: 0,
        size: 20,
        hasNext: false,
        items: [
          {
            id: auditId,
            targetType: 'ARTISTS',
            targetId: id,
            actorId: null,
            action: 'UPDATE',
            before: { name: '이전' },
            after: { name: '최신' },
            reason: '확인',
            changedAt: '2026-11-01T00:00:00Z',
          },
        ],
      },
    })
  })
  await page.goto(editPath)
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '아티스트 찾기·추가', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
    .click()
  const form = page.getByRole('dialog').last()
  await form
    .getByLabel('아티스트 이름*', { exact: true })
    .fill('유지할 아티스트')
  await form.getByRole('button', { name: '변경 이력', exact: true }).click()
  await page
    .getByRole('button', { name: '입력을 유지하고 이력 열기', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .last()
    .getByRole('button', { name: '원래 화면으로', exact: true })
    .click()
  await expect(
    page
      .getByRole('dialog')
      .last()
      .getByLabel('아티스트 이름*', { exact: true }),
  ).toHaveValue('유지할 아티스트')
  expect(s.artistWrites).toHaveLength(0)
  expect(s.workPuts).toHaveLength(0)
})
