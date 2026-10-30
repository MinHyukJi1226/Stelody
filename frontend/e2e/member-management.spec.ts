import { test, expect, makeSong } from './fixtures'
import type { Page } from '@playwright/test'
import { mockAdmin } from './admin-fixtures'
import type { AdminMember, MemberInput } from '../src/features/admin/members'

const id = makeSong(950).id
const original = '/admin/members?q=멤버&page=1&size=10'
const path = `/admin/members/${id}/edit?returnTo=${encodeURIComponent(original)}`
const fresh = `/admin/members/new?returnTo=${encodeURIComponent(original)}`
async function setup(page: Page) {
  const admin = await mockAdmin(page)
  const state = {
    admin,
    member: {
      id,
      version: 4,
      name: '테스트 멤버',
      unitName: '테스트 유닛',
      generation: 2,
      activityStatus: 'ACTIVE',
      profileImageUrl: null,
      chzzkUrl: 'https://chzzk.naver.com/member',
      xUrl: 'https://x.com/member',
      birthdayMonth: 2,
      birthdayDay: 29,
      debutDate: '2024-02-29',
      aliases: ['별명'],
    } as AdminMember,
    writes: [] as { method: string; body: MemberInput }[],
    lists: [] as URL[],
    mode: 'success',
    readError: 0,
    listError: 0,
    empty: false,
    gate: Promise.resolve(),
    readGate: Promise.resolve(),
    duplicates: [] as string[],
  }
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route('**/api/v1/admin/members**', async (route) => {
    const req = route.request(),
      url = new URL(req.url())
    if (req.method() === 'GET') {
      await state.readGate
      if (url.pathname === '/api/v1/admin/members') {
        state.lists.push(url)
        return route.fulfill(
          state.listError
            ? { status: state.listError, json: { code: 'UNAVAILABLE' } }
            : {
                json: {
                  page: Number(url.searchParams.get('page')),
                  size: Number(url.searchParams.get('size')),
                  hasNext: Number(url.searchParams.get('page')) === 0,
                  items: state.empty
                    ? []
                    : [
                        {
                          id: state.member.id,
                          version: state.member.version,
                          name: state.member.name,
                          status: state.member.activityStatus,
                        },
                      ],
                },
              },
        )
      }
      return route.fulfill(
        state.readError
          ? { status: state.readError, json: { code: 'UNAVAILABLE' } }
          : { json: state.member },
      )
    }
    const body = req.postDataJSON() as MemberInput
    state.writes.push({ method: req.method(), body })
    expect(req.headers()['x-csrf-token']).toBe('test-csrf')
    await state.gate
    if (state.mode === 'denied')
      return route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } })
    if (state.mode === 'invalid')
      return route.fulfill({
        status: 400,
        json: {
          code: 'INVALID_CATALOG_REQUEST',
          title: '입력 확인',
          fieldErrors: [
            {
              field: 'birthdayDay',
              code: 'INVALID_VALUE',
              message: '생일을 확인해주세요',
            },
            {
              field: 'aliases[0]',
              code: 'INVALID_VALUE',
              message: '별칭을 확인해주세요',
            },
          ],
        },
      })
    if (state.mode === 'conflict')
      return route.fulfill({
        status: 409,
        json: { code: 'CATALOG_VERSION_CONFLICT' },
      })
    state.member = {
      ...body,
      id: req.method() === 'POST' ? makeSong(951).id : state.member.id,
      version: req.method() === 'POST' ? 0 : body.version + 1,
      unitName: body.unitName ?? null,
      generation: body.generation ?? null,
      chzzkUrl: body.chzzkUrl ?? null,
      xUrl: body.xUrl ?? null,
      profileImageUrl: body.profileImageUrl ?? null,
      birthdayMonth: body.birthdayMonth ?? null,
      birthdayDay: body.birthdayDay ?? null,
      debutDate: body.debutDate ?? null,
    }
    if (state.mode === 'lost') return route.abort('failed')
    return route.fulfill({
      status: req.method() === 'POST' ? 201 : 200,
      json: { item: state.member, possibleDuplicateIds: state.duplicates },
    })
  })
  return state
}
async function fill(page: Page) {
  await page.getByLabel('멤버 이름*', { exact: true }).fill('수정 멤버')
  await page.getByLabel('변경 사유*', { exact: true }).fill('공식 정보 확인')
}
async function save(page: Page) {
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
}
test('관리 메뉴 진입·검색·페이지 이동과 원래 문맥 복귀', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '멤버 관리', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '멤버 관리', exact: true }),
  ).toBeVisible()
  await page.getByLabel('검색', { exact: true }).fill('별명')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page).toHaveURL(
    '/admin/members?q=%EB%B3%84%EB%AA%85&page=1&size=20',
  )
  await page.getByRole('link', { name: '멤버 수정', exact: true }).click()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL(
    '/admin/members?q=%EB%B3%84%EB%AA%85&page=1&size=20',
  )
  expect(
    state.lists.some(
      (u) =>
        u.searchParams.get('q') === '별명' &&
        u.searchParams.get('page') === '1',
    ),
  ).toBe(true)
})
test('전체 필드 보존·변경 저장·검색 문맥 복귀·새로고침 유지', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toEqual({
    method: 'PUT',
    body: {
      version: 4,
      name: '수정 멤버',
      unitName: '테스트 유닛',
      generation: 2,
      activityStatus: 'ACTIVE',
      chzzkUrl: 'https://chzzk.naver.com/member',
      xUrl: 'https://x.com/member',
      birthdayMonth: 2,
      birthdayDay: 29,
      debutDate: '2024-02-29',
      aliases: ['별명'],
      reason: '공식 정보 확인',
    },
  })
  await page.getByRole('link', { name: '멤버 수정', exact: true }).click()
  await page.reload()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
})
test('멤버 등록은 확인 후 한 번 실행하고 유사 멤버와 등록된 멤버를 안내한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.duplicates = [id]
  await page.goto(fresh)
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toBeEnabled()
  await expect(page.getByLabel('생일 · 월 / 일', { exact: true })).toHaveValue(
    '',
  )
  await fill(page)
  await page.getByLabel('유닛명', { exact: true }).fill('새 유닛')
  await page.getByLabel('기수', { exact: true }).fill('3')
  await page.getByLabel('생일 · 월 / 일', { exact: true }).fill('2 / 29')
  await page
    .getByRole('button', { name: '활동 상태: 활동 중', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '졸업', exact: true })
    .click()
  await page.getByRole('button', { name: '멤버 등록', exact: true }).click()
  expect(state.writes).toHaveLength(0)
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '멤버를 등록했습니다', exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toMatchObject({
    method: 'POST',
    body: {
      version: 0,
      name: '수정 멤버',
      unitName: '새 유닛',
      generation: 3,
      activityStatus: 'GRADUATED',
      birthdayMonth: 2,
      birthdayDay: 29,
    },
  })
  await expect(
    page.getByRole('link', { name: '유사 멤버 1 확인', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('link', { name: '등록한 멤버 열기', exact: true })
    .click()
  await expect(page.getByLabel('유닛명', { exact: true })).toHaveValue(
    '새 유닛',
  )
})
test('선택 필드를 모두 지운 저장은 기존 값도 삭제한다', async ({ page }) => {
  const state = await setup(page)
  state.member.profileImageUrl = 'https://example.com/photo.png'
  await page.goto(path)
  for (const label of [
    '유닛명',
    '기수',
    '프로필 이미지 URL',
    '치지직 공식 링크',
    'X 공식 링크',
    '생일 · 월 / 일',
    '데뷔일',
  ])
    await page.getByLabel(label, { exact: true }).fill('')
  await page
    .getByRole('button', { name: '별칭 별명 삭제', exact: true })
    .click()
  await page.getByLabel('변경 사유*', { exact: true }).fill('선택 정보 제거')
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[0]!.body).toEqual({
    version: 4,
    name: '테스트 멤버',
    activityStatus: 'ACTIVE',
    aliases: [],
    unitName: null,
    chzzkUrl: null,
    xUrl: null,
    reason: '선택 정보 제거',
  })
  await page.getByRole('link', { name: '멤버 수정', exact: true }).click()
  await expect(page.getByLabel('생일 · 월 / 일', { exact: true })).toHaveValue(
    '',
  )
  await expect(page.getByRole('img', { name: '프로필 미리보기' })).toHaveText(
    '사진',
  )
})
test('잘못된 기수·생일·URL은 저장하지 않고 초안을 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByLabel('기수', { exact: true }).fill('0')
  await page.getByLabel('생일 · 월 / 일', { exact: true }).fill('2 / 30')
  await page
    .getByLabel('X 공식 링크', { exact: true })
    .fill('http://example.com')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  for (const label of ['기수', '생일 · 월 / 일', 'X 공식 링크'])
    await expect(page.getByLabel(label, { exact: true })).toHaveAttribute(
      'aria-invalid',
      'true',
    )
  expect(state.writes).toHaveLength(0)
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
})
test('별칭은 초안에서 추가·제거하고 중복이나 미추가 입력을 저장하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByLabel('검색 별칭 · 선택', { exact: true }).fill('별명')
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await expect(
    page
      .getByText('공백·대소문자가 같은 별칭이 있습니다.', { exact: true })
      .first(),
  ).toBeVisible()
  await page.getByLabel('검색 별칭 · 선택', { exact: true }).fill('새 별칭')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page
      .getByText('입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.', {
        exact: true,
      })
      .first(),
  ).toBeVisible()
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await page
    .getByRole('button', { name: '별칭 별명 삭제', exact: true })
    .click()
  expect(state.writes).toHaveLength(0)
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[0]!.body.aliases).toEqual(['새 별칭'])
})
test('서버의 생일·배열 필드 오류는 해당 입력에 연결한다', async ({ page }) => {
  const state = await setup(page)
  state.mode = 'invalid'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByLabel('생일 · 월 / 일', { exact: true }),
  ).toHaveAttribute('aria-invalid', 'true')
  await expect(
    page.getByText('별칭을 확인해주세요', { exact: true }).last(),
  ).toBeVisible()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
  expect(state.writes).toHaveLength(1)
})
test('버전 충돌은 최신 전체 필드를 비교하고 명시적 재확인 뒤 새 버전으로 저장한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'conflict'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByRole('heading', {
      name: '다른 관리자가 먼저 변경했습니다',
      exact: true,
    }),
  ).toBeVisible()
  state.member = {
    ...state.member,
    name: '다른 관리자의 이름',
    unitName: '다른 유닛',
    version: 5,
  }
  await page
    .getByRole('button', { name: '최신 저장값 조회', exact: true })
    .click()
  await expect(page.getByText('다른 유닛', { exact: true })).toBeVisible()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
  state.mode = 'success'
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[1]!.body.version).toBe(5)
})
test('수정 응답 유실은 최신 값이 같아도 자동 완료·재전송하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'lost'
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
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '원래 목록으로', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인하지 않고 이동할까요?',
      exact: true,
    }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL(original)
})
test('등록 응답 유실은 중복 등록을 막고 입력한 이름의 목록에서 확인한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'lost'
  await page.goto(fresh)
  await fill(page)
  await page.getByRole('button', { name: '멤버 등록', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인할 수 없습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '멤버 등록', exact: true }),
  ).toBeDisabled()
  await page
    .getByRole('button', { name: '멤버 목록에서 확인', exact: true })
    .click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL(
    '/admin/members?q=%EC%88%98%EC%A0%95+%EB%A9%A4%EB%B2%84&page=0&size=20',
  )
  expect(state.writes).toHaveLength(1)
})
test('저장 중 입력·이동·중복 실행을 잠근다', async ({ page }) => {
  const state = await setup(page)
  let release!: () => void
  state.gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByRole('heading', { name: '저장 중입니다', exact: true }),
  ).toBeVisible()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '확인 후 저장', exact: true }),
  ).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(state.writes).toHaveLength(1)
  release()
  await expect(page).toHaveURL(original)
})
test('미저장 메뉴 이동은 목적지를 보존하고 취소하면 입력이 남는다', async ({
  page,
}) => {
  await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '원곡 관리', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장하지 않고 이동할까요?',
      exact: true,
    }),
  ).toBeVisible()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '원곡 관리', exact: true }).click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin/works')
})
test('저장 403 후에도 버리기 확인을 통해 홈으로 나갈 수 있다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'denied'
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
test('세션 조회 장애 후 재조회는 초안을 보존하고 저장을 다시 허용한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', {
      name: '권한을 확인하지 못했어요',
      exact: true,
    }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Stelody 홈', exact: true }).click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  state.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '수정 멤버',
  )
  await save(page)
  await expect(page).toHaveURL(original)
})
test('목록 실패·빈 상태는 검색·페이지를 유지하고 등록 진입을 제공한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.listError = 503
  await page.goto(original)
  await expect(
    page.getByRole('heading', {
      name: '멤버 목록을 조회하지 못했습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(page).toHaveURL(original)
  state.listError = 0
  state.empty = true
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '목록이 비어 있습니다', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '멤버 등록', exact: true }),
  ).toBeVisible()
  expect(state.lists.every((u) => u.searchParams.get('page') === '1')).toBe(
    true,
  )
})
for (const status of [404, 403, 401])
  test(`멤버 상세 ${status} 상태를 구분한다`, async ({ page }) => {
    const state = await setup(page)
    state.readError = status
    await page.goto(path)
    await expect(
      page.getByRole('heading', {
        name:
          status === 404
            ? '멤버를 찾을 수 없습니다'
            : status === 403
              ? '관리자 권한이 없어요'
              : '로그인이 필요해요',
        exact: true,
      }),
    ).toBeVisible()
    expect(state.writes).toHaveLength(0)
  })
test('조회 중·조회 실패·재조회에서 임의 입력을 노출하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  let release!: () => void
  state.readGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto(path)
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveCount(0)
  state.readError = 503
  release()
  await expect(
    page.getByRole('heading', {
      name: '멤버 정보를 조회하지 못했습니다',
      exact: true,
    }),
  ).toBeVisible()
  state.readError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByLabel('멤버 이름*', { exact: true })).toHaveValue(
    '테스트 멤버',
  )
})
test('라이트·다크 목록·등록·수정과 상태 선택창은 화면 너비를 넘지 않는다', async ({
  page,
}, testInfo) => {
  await setup(page)
  for (const theme of ['light', 'dark']) {
    await page.goto('/admin/members')
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    for (const [screen, url] of [
      ['list', '/admin/members'],
      ['create', fresh],
      ['edit', path],
    ]) {
      await page.goto(url!)
      await expect(
        page.getByRole('heading', {
          name:
            screen === 'list'
              ? '멤버 관리'
              : screen === 'create'
                ? '멤버 등록'
                : '멤버 수정',
          exact: true,
        }),
      ).toBeVisible()
      if (screen !== 'list')
        await expect(
          page.getByLabel('멤버 이름*', { exact: true }),
        ).toBeEnabled()
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true)
      await page.screenshot({
        path: testInfo.outputPath(`member-${screen}-${theme}.png`),
        fullPage: true,
      })
    }
    await page
      .getByRole('button', { name: '활동 상태: 활동 중', exact: true })
      .click()
    expect(
      await page
        .getByRole('dialog')
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true)
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '취소', exact: true })
      .click()
  }
})
