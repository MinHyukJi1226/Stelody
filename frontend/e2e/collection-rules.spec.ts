import { test, expect } from './fixtures'
import type { Page } from '@playwright/test'
import { mockAdmin } from './admin-fixtures'
import type {
  RuleSnapshot,
  Sample,
} from '../src/features/admin/collection-rules'
async function setup(page: Page) {
  const admin = await mockAdmin(page)
  const state = {
    admin,
    snapshot: {
      version: 5,
      configuration: {
        cover: [{ text: 'cover', match: 'WORD' }],
        original: [{ text: 'original', match: 'WORD' }],
        exclude: [{ text: 'clip', match: 'PHRASE' }],
      },
    } as RuleSnapshot,
    mode: 'success',
    readError: 0,
    gate: Promise.resolve(),
    writes: [] as (RuleSnapshot & { reason: string })[],
    previews: [] as (RuleSnapshot & { samples: Sample[] })[],
  }
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route('**/api/v1/admin/collection-rules**', async (r) => {
    const req = r.request()
    if (req.method() === 'GET')
      return r.fulfill(
        state.readError
          ? { status: state.readError, json: { code: 'UNAVAILABLE' } }
          : { json: state.snapshot },
      )
    const b = req.postDataJSON()
    expect(req.headers()['x-csrf-token']).toBe('test-csrf')
    if (req.method() === 'POST') {
      state.previews.push(b)
      await state.gate
      if (state.mode === 'conflict')
        return r.fulfill({
          status: 409,
          json: { code: 'CATALOG_VERSION_CONFLICT' },
        })
      if (state.mode === 'previewError')
        return r.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } })
      return r.fulfill({
        json: {
          baseVersion: state.mode === 'malformed' ? 99 : b.version,
          decisions: b.samples.map((s: Sample) => ({
            disposition: s.availability === 'PUBLIC' ? 'REVIEW' : 'DEFERRED',
            suggestedType: s.availability === 'PUBLIC' ? 'COVER' : 'UNKNOWN',
            reason:
              s.availability === 'PUBLIC'
                ? 'COVER_REQUIRES_PARTICIPANT_REVIEW'
                : 'NOT_PUBLIC',
            version: 'preview:5',
          })),
        },
      })
    }
    state.writes.push(b)
    await state.gate
    if (state.mode === 'conflict')
      return r.fulfill({
        status: 409,
        json: { code: 'CATALOG_VERSION_CONFLICT' },
      })
    if (state.mode === 'denied')
      return r.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    if (state.mode === 'invalid')
      return r.fulfill({
        status: 400,
        json: {
          code: 'INVALID_CATALOG_REQUEST',
          fieldErrors: [
            {
              field: 'configuration.cover[0].text',
              code: 'INVALID_VALUE',
              message: '표기를 확인해주세요',
            },
          ],
        },
      })
    state.snapshot = { version: b.version + 1, configuration: b.configuration }
    if (state.mode === 'lost') return r.abort('failed')
    return r.fulfill({ json: state.snapshot })
  })
  return state
}
async function fill(page: Page) {
  await page
    .getByLabel('커버 규칙 1 단어·문구*', { exact: true })
    .fill('new cover')
  await page.getByLabel('변경 사유*', { exact: true }).fill('공식 표기 확인')
}
async function save(page: Page) {
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
}
async function preview(page: Page) {
  await page.getByRole('button', { name: '샘플 판정', exact: true }).click()
  await page.getByLabel('샘플 1 제목*', { exact: true }).fill('new cover 샘플')
  await page.getByRole('button', { name: '샘플 판정하기', exact: true }).click()
}
test('관리 메뉴 진입·배열 추가·삭제·일치 방식·저장 및 새로고침 유지', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page
    .getByRole('link', { name: '수집 분류 규칙 관리', exact: true })
    .click()
  await fill(page)
  const group = page.getByRole('region', { name: '커버 규칙', exact: true })
  await group.getByRole('button', { name: '규칙 추가', exact: true }).click()
  await page.getByLabel('커버 규칙 2 단어·문구*', { exact: true }).fill('노래')
  await page
    .getByRole('button', { name: '커버 규칙 2 일치 방식', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '문구', exact: true })
    .click()
  expect(s.writes).toHaveLength(0)
  await save(page)
  await expect(page).toHaveURL('/admin')
  expect(s.writes[0]).toMatchObject({
    version: 5,
    reason: '공식 표기 확인',
    configuration: {
      cover: [
        { text: 'new cover', match: 'WORD' },
        { text: '노래', match: 'PHRASE' },
      ],
    },
  })
  await page.goto('/admin/collection-rules')
  await page.reload()
  await expect(
    page.getByLabel('커버 규칙 2 단어·문구*', { exact: true }),
  ).toHaveValue('노래')
})
test('규칙 검증과 서버 필드 오류에서 입력을 보존한다', async ({ page }) => {
  const s = await setup(page)
  await page.goto('/admin/collection-rules')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(s.writes).toHaveLength(0)
  await fill(page)
  s.mode = 'invalid'
  await save(page)
  await expect(
    page.getByLabel('커버 규칙 1 단어·문구*', { exact: true }),
  ).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
    '공식 표기 확인',
  )
})
test('미저장 규칙으로 판정하고 샘플 변경 시 이전 결과를 숨긴다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto('/admin/collection-rules')
  await fill(page)
  await preview(page)
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toBeVisible()
  expect(s.writes).toHaveLength(0)
  expect(s.previews[0]!.configuration.cover[0]!.text).toBe('new cover')
  await page
    .getByRole('button', { name: '샘플 1 영상 공개 상태', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'PRIVATE', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '샘플 판정하기', exact: true }).click()
  await expect(
    page.getByText('PRIVATE · DEFERRED / UNKNOWN', { exact: true }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '이 규칙 저장 확인', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
  await expect(page).toHaveURL('/admin')
})
test('규칙 수정 뒤 기존 판정은 무효이며 샘플 입력을 유지한다', async ({
  page,
}) => {
  await setup(page)
  await page.goto('/admin/collection-rules')
  await preview(page)
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '규칙 입력으로 돌아가기', exact: true })
    .click()
  await page
    .getByLabel('커버 규칙 1 단어·문구*', { exact: true })
    .fill('다른 규칙')
  await page.getByRole('button', { name: '샘플 판정', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByLabel('샘플 1 제목*', { exact: true })).toHaveValue(
    'new cover 샘플',
  )
})
for (const mode of ['previewError', 'malformed'])
  test(`샘플 ${mode} 응답을 결과로 표시하지 않고 재판정한다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.mode = mode
    await page.goto('/admin/collection-rules')
    await preview(page)
    await expect(
      page.getByText(
        '샘플 판정에 실패했습니다. 입력 규칙과 샘플을 유지했습니다.',
        { exact: true },
      ),
    ).toBeVisible()
    expect(s.writes).toHaveLength(0)
    s.mode = 'success'
    await page
      .getByRole('button', { name: '샘플 판정하기', exact: true })
      .click()
    await expect(
      page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
    ).toBeVisible()
  })
for (const during of ['save', 'preview'])
  test(`${during} 버전 충돌에서 비교·재확인 후 최신 버전을 사용한다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.mode = 'conflict'
    await page.goto('/admin/collection-rules')
    await fill(page)
    if (during === 'save') await save(page)
    else await preview(page)
    await expect(
      page.getByRole('heading', {
        name: '다른 관리자가 먼저 변경했습니다',
        exact: true,
      }),
    ).toBeVisible()
    s.snapshot.version = 6
    s.snapshot.configuration.original[0]!.text = '최신 규칙'
    await page
      .getByRole('button', { name: '최신 저장값 조회', exact: true })
      .click()
    await expect(
      page.getByText('최신 규칙 · 단어', { exact: true }),
    ).toBeVisible()
    await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
    await expect(
      page.getByLabel('커버 규칙 1 단어·문구*', { exact: true }),
    ).toHaveValue('new cover')
    s.mode = 'success'
    await save(page)
    await expect(page).toHaveURL('/admin')
    expect(s.writes.at(-1)!.version).toBe(6)
  })
test('저장 응답 유실은 값이 같아도 성공으로 추정하거나 재전송하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.mode = 'lost'
  await page.goto('/admin/collection-rules')
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
  expect(s.writes).toHaveLength(1)
  await page.getByRole('button', { name: '작업함으로', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인하지 않고 이동할까요?',
      exact: true,
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin')
})
for (const during of ['save', 'preview'])
  test(`${during} 중 입력·중복 실행·이동 잠금`, async ({ page }) => {
    const s = await setup(page)
    let release!: () => void
    s.gate = new Promise<void>((r) => {
      release = r
    })
    await page.goto('/admin/collection-rules')
    await fill(page)
    if (during === 'save') {
      await save(page)
      await expect(
        page.getByRole('button', { name: '확인 후 저장', exact: true }),
      ).toBeDisabled()
      await page.keyboard.press('Escape')
      await expect(page.getByRole('dialog')).toBeVisible()
    } else {
      await preview(page)
      await expect(
        page.getByRole('button', { name: '샘플 판정하기', exact: true }),
      ).toBeDisabled()
      await page.getByRole('link', { name: 'Stelody 홈', exact: true }).click()
      await expect(page).toHaveURL('/admin/collection-rules')
    }
    release()
    if (during === 'save') await expect(page).toHaveURL('/admin')
    else
      await expect(
        page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
      ).toBeVisible()
  })
test('403 이후와 세션 오류에서도 미저장 이동 확인을 사용할 수 있다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto('/admin/collection-rules')
  await fill(page)
  s.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', {
      name: '권한을 확인하지 못했어요',
      exact: true,
    }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Stelody 홈', exact: true }).click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  s.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByLabel('커버 규칙 1 단어·문구*', { exact: true }),
  ).toHaveValue('new cover')
  s.mode = 'denied'
  await save(page)
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('main')
    .getByRole('link', { name: '홈으로', exact: true })
    .click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/')
})
for (const status of [401, 403, 503])
  test(`조회 ${status}는 빈 규칙으로 표시하지 않는다`, async ({ page }) => {
    const s = await setup(page)
    s.readError = status
    await page.goto('/admin/collection-rules')
    await expect(
      page.getByRole('heading', {
        name:
          status === 401
            ? '로그인이 필요해요'
            : status === 403
              ? '관리자 권한이 없어요'
              : '분류 규칙을 조회하지 못했습니다',
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByLabel('커버 규칙 1 단어·문구*', { exact: true }),
    ).toHaveCount(0)
  })
test('빈 배열 저장과 샘플 추가·삭제 제한을 지원한다', async ({ page }) => {
  const s = await setup(page)
  s.snapshot.configuration = { cover: [], original: [], exclude: [] }
  await page.goto('/admin/collection-rules')
  await page.getByLabel('변경 사유*', { exact: true }).fill('초기화')
  await page.getByRole('button', { name: '샘플 판정', exact: true }).click()
  await page.getByRole('button', { name: '샘플 삭제', exact: true }).click()
  await page.getByRole('button', { name: '샘플 판정하기', exact: true }).click()
  await expect(
    page.getByText('샘플을 1–20개 입력해주세요.', { exact: true }).first(),
  ).toBeVisible()
  await page.getByRole('button', { name: '샘플 추가', exact: true }).click()
  await expect(page.getByLabel('샘플 1 제목*', { exact: true })).toHaveValue('')
  await page
    .getByRole('button', { name: '규칙 입력으로 돌아가기', exact: true })
    .click()
  await save(page)
  await expect(page).toHaveURL('/admin')
  expect(s.writes[0]!.configuration).toEqual({
    cover: [],
    original: [],
    exclude: [],
  })
})
test('라이트·다크 규칙 편집·샘플 입력·결과는 가로 넘침이 없다', async ({
  page,
}, info) => {
  await setup(page)
  for (const theme of ['light', 'dark']) {
    await page.goto('/admin/collection-rules')
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await expect(
      page.getByLabel('커버 규칙 1 단어·문구*', { exact: true }),
    ).toBeEnabled()
    await page.screenshot({
      path: info.outputPath(`rules-${theme}.png`),
      fullPage: true,
    })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await preview(page)
    await expect(
      page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
    ).toBeVisible()
    await page.screenshot({
      path: info.outputPath(`preview-${theme}.png`),
      fullPage: true,
    })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
  }
})

test('판정 중 세션 확인 실패는 결과를 수용하지 않고 샘플 입력을 보존한다', async ({
  page,
}) => {
  const s = await setup(page)
  let release!: () => void
  s.gate = new Promise<void>((r) => {
    release = r
  })
  await page.goto('/admin/collection-rules')
  await preview(page)
  await expect(
    page.getByRole('button', { name: '샘플 판정하기', exact: true }),
  ).toBeDisabled()
  s.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', {
      name: '권한을 확인하지 못했어요',
      exact: true,
    }),
  ).toBeVisible()
  release()
  s.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByLabel('샘플 1 제목*', { exact: true })).toHaveValue(
    'new cover 샘플',
  )
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '샘플 판정하기', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '샘플 판정 결과', exact: true }),
  ).toBeVisible()
})
