import { test, expect, makeSong } from './fixtures'
import { mockAdmin } from './admin-fixtures'
import type { Page } from '@playwright/test'
import type { AdminChannel, ChannelInput } from '../src/features/admin/channels'
const id = makeSong(960).id,
  mid = makeSong(961).id
const original = '/admin/channels?q=official&page=1&size=10'
const path = `/admin/channels/${id}/edit?returnTo=${encodeURIComponent(original)}`
const fresh = `/admin/channels/new?returnTo=${encodeURIComponent(original)}`
async function setup(page: Page) {
  const admin = await mockAdmin(page)
  const state = {
    admin,
    channel: {
      id,
      version: 2,
      youtubeId: 'UC' + 'a'.repeat(22),
      name: '공식 채널',
      memberId: mid,
      channelType: 'MEMBER',
      collectionEnabled: true,
    } as AdminChannel,
    writes: [] as ChannelInput[],
    mode: 'success',
    readError: 0,
    listError: 0,
    empty: false,
    gate: Promise.resolve(),
  }
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route('**/api/v1/admin/members**', (r) => {
    const u = new URL(r.request().url())
    return r.fulfill({
      json: u.pathname.endsWith('/members')
        ? {
            page: Number(u.searchParams.get('page')),
            size: 20,
            hasNext: false,
            items: [
              { id: mid, name: '테스트 멤버', version: 1, status: 'ACTIVE' },
            ],
          }
        : {
            id: mid,
            name: '테스트 멤버',
            version: 1,
            activityStatus: 'ACTIVE',
            generation: null,
            unitName: null,
            profileImageUrl: null,
            chzzkUrl: null,
            xUrl: null,
            birthdayMonth: null,
            birthdayDay: null,
            debutDate: null,
            aliases: [],
          },
    })
  })
  await page.route('**/api/v1/admin/channels**', async (r) => {
    const req = r.request(),
      u = new URL(req.url())
    if (req.method() === 'GET')
      return r.fulfill(
        u.pathname.endsWith('/channels')
          ? state.listError
            ? { status: 503, json: { code: 'UNAVAILABLE' } }
            : {
                json: {
                  page: Number(u.searchParams.get('page')),
                  size: Number(u.searchParams.get('size')),
                  hasNext: Number(u.searchParams.get('page')) === 0,
                  items: state.empty
                    ? []
                    : [
                        {
                          id,
                          name: state.channel.name,
                          version: state.channel.version,
                          status: state.channel.channelType,
                        },
                      ],
                },
              }
          : state.readError
            ? { status: state.readError, json: { code: 'UNAVAILABLE' } }
            : { json: state.channel },
      )
    const b = req.postDataJSON() as ChannelInput
    state.writes.push(b)
    expect(req.headers()['x-csrf-token']).toBe('test-csrf')
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
              field: 'name',
              code: 'INVALID_VALUE',
              message: '공식 채널명을 확인해주세요',
            },
          ],
        },
      })
    state.channel = {
      ...b,
      id: req.method() === 'POST' ? makeSong(962).id : id,
      version: req.method() === 'POST' ? 0 : b.version + 1,
      memberId: b.memberId ?? null,
    }
    if (state.mode === 'lost') return r.abort('failed')
    return r.fulfill({
      status: req.method() === 'POST' ? 201 : 200,
      json: state.channel,
    })
  })
  return state
}
async function fill(page: Page) {
  await page.getByLabel('채널명*', { exact: true }).fill('변경 채널')
  await page.getByLabel('변경 사유*', { exact: true }).fill('공식 채널 확인')
}
async function save(page: Page) {
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
}
test('관리 메뉴·검색·페이지 이동과 저장 후 목록 복귀', async ({ page }) => {
  const s = await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '공식 채널 관리', exact: true }).click()
  await page.getByLabel('검색', { exact: true }).fill('official')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await page.getByRole('link', { name: '채널 수정', exact: true }).click()
  await fill(page)
  await save(page)
  await expect(page).toHaveURL('/admin/channels?q=official&page=1&size=20')
  expect(s.writes[0]).toMatchObject({
    version: 2,
    memberId: mid,
    collectionEnabled: true,
    youtubeId: 'UC' + 'a'.repeat(22),
    channelType: 'MEMBER',
  })
  await page.getByRole('link', { name: '채널 수정', exact: true }).click()
  await page.reload()
  await expect(page.getByLabel('채널명*', { exact: true })).toHaveValue(
    '변경 채널',
  )
})
test('채널 ID·유형은 읽기 전용이고 멤버 연결을 초안에서 제거할 수 있다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(path)
  await expect(
    page.getByLabel('YouTube 채널 ID*', { exact: true }),
  ).toHaveAttribute('readonly', '')
  await expect(
    page.getByRole('button', { name: '채널 유형: 멤버', exact: true }),
  ).toBeDisabled()
  await page
    .getByRole('button', { name: '연결 멤버 선택', exact: true })
    .click()
  await page.getByRole('radio', { name: '연결하지 않음', exact: true }).check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  expect(s.writes).toHaveLength(0)
  await fill(page)
  await save(page)
  await expect(page).toHaveURL(original)
  expect(s.writes[0]).not.toHaveProperty('memberId')
})
test('외부 채널 등록은 수집·멤버 연결을 해제하고 성공 응답 후 완료한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(fresh)
  await fill(page)
  await page
    .getByLabel('YouTube 채널 ID*', { exact: true })
    .fill('UC' + 'b'.repeat(22))
  await page
    .getByRole('button', { name: '채널 유형: 그룹', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '외부', exact: true })
    .click()
  await expect(
    page.getByRole('button', { name: '연결 멤버 선택', exact: true }),
  ).toBeDisabled()
  await expect(
    page.getByRole('button', {
      name: '수집 사용 여부: 사용 안 함',
      exact: true,
    }),
  ).toBeDisabled()
  await page.getByRole('button', { name: '채널 등록', exact: true }).click()
  expect(s.writes).toHaveLength(0)
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '채널을 등록했습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes[0]).toMatchObject({
    version: 0,
    channelType: 'EXTERNAL',
    collectionEnabled: false,
  })
  await expect(
    page.getByRole('link', { name: '등록한 채널 열기', exact: true }),
  ).toBeVisible()
})
test('잘못된 채널 ID와 서버 필드 오류에서 입력을 보존한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(fresh)
  await fill(page)
  await page.getByRole('button', { name: '채널 등록', exact: true }).click()
  await expect(
    page.getByLabel('YouTube 채널 ID*', { exact: true }),
  ).toHaveAttribute('aria-invalid', 'true')
  expect(s.writes).toHaveLength(0)
  await page
    .getByLabel('YouTube 채널 ID*', { exact: true })
    .fill('UC' + 'b'.repeat(22))
  s.mode = 'invalid'
  await page.getByRole('button', { name: '채널 등록', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(page.getByLabel('채널명*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.getByLabel('채널명*', { exact: true })).toHaveValue(
    '변경 채널',
  )
})
test('버전 충돌은 비교·명시적 재확인 후 최신 버전으로 저장한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.mode = 'conflict'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(
    page.getByRole('heading', {
      name: '다른 관리자가 먼저 변경했습니다',
      exact: true,
    }),
  ).toBeVisible()
  s.channel = { ...s.channel, version: 3, name: '최신 채널' }
  await page
    .getByRole('button', { name: '최신 저장값 조회', exact: true })
    .click()
  await expect(page.getByText('최신 채널', { exact: true })).toBeVisible()
  expect(s.writes).toHaveLength(1)
  await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
  s.mode = 'success'
  await save(page)
  await expect(page).toHaveURL(original)
  expect(s.writes[1]!.version).toBe(3)
})
for (const create of [false, true])
  test(`${create ? '등록' : '수정'} 응답 유실을 자동 완료·재전송하지 않는다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.mode = 'lost'
    await page.goto(create ? fresh : path)
    await fill(page)
    if (create) {
      await page
        .getByLabel('YouTube 채널 ID*', { exact: true })
        .fill('UC' + 'b'.repeat(22))
      await page.getByRole('button', { name: '채널 등록', exact: true }).click()
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '등록', exact: true })
        .click()
    } else await save(page)
    await expect(
      page.getByRole('heading', {
        name: '저장 결과를 확인할 수 없습니다',
        exact: true,
      }),
    ).toBeVisible()
    if (!create) {
      await page
        .getByRole('button', { name: '최신 저장값 조회', exact: true })
        .click()
      await expect(
        page.getByRole('heading', {
          name: '최신 내용을 확인했습니다',
          exact: true,
        }),
      ).toBeVisible()
    }
    expect(s.writes).toHaveLength(1)
    await page
      .getByRole('button', { name: '원래 목록으로', exact: true })
      .click()
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
test('저장 중 입력·중복 제출을 잠근다', async ({ page }) => {
  const s = await setup(page)
  let release!: () => void
  s.gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(page.getByLabel('채널명*', { exact: true })).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '확인 후 저장', exact: true }),
  ).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeVisible()
  release()
  await expect(page).toHaveURL(original)
  expect(s.writes).toHaveLength(1)
})
test('저장 403 이후에도 확인창에서 이동할 수 있다', async ({ page }) => {
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
for (const status of [401, 403, 404])
  test(`상세 ${status} 분리`, async ({ page }) => {
    const s = await setup(page)
    s.readError = status
    await page.goto(path)
    await expect(
      page.getByRole('heading', {
        name:
          status === 404
            ? '채널을 찾을 수 없습니다'
            : status === 401
              ? '로그인이 필요해요'
              : '관리자 권한이 없어요',
        exact: true,
      }),
    ).toBeVisible()
  })
test('목록 오류·빈 상태를 구분한다', async ({ page }) => {
  const s = await setup(page)
  s.listError = 503
  await page.goto(original)
  await expect(
    page.getByRole('heading', {
      name: '채널 목록을 조회하지 못했습니다',
      exact: true,
    }),
  ).toBeVisible()
  s.listError = 0
  s.empty = true
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '목록이 비어 있습니다', exact: true }),
  ).toBeVisible()
  await expect(page).toHaveURL(original)
})
test('PC·모바일 라이트·다크 목록·편집·선택창의 가로 넘침이 없다', async ({
  page,
}, info) => {
  await setup(page)
  for (const theme of ['light', 'dark']) {
    await page.goto(path)
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await expect(page.getByLabel('채널명*', { exact: true })).toBeEnabled()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: info.outputPath(`channel-edit-${theme}.png`),
      fullPage: true,
    })
    await page
      .getByRole('button', { name: '연결 멤버 선택', exact: true })
      .click()
    await expect(
      page.getByRole('radio', { name: '테스트 멤버', exact: true }),
    ).toBeVisible()
    expect(
      await page
        .getByRole('dialog')
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true)
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '취소', exact: true })
      .click()
    await page.goto('/admin/channels')
    await expect(
      page.getByRole('heading', { name: '공식 채널 관리', exact: true }),
    ).toBeVisible()
    await page.screenshot({
      path: info.outputPath(`channel-list-${theme}.png`),
      fullPage: true,
    })
  }
})

test('신규 멤버 채널에서 멤버 선택·수집 사용을 확인한 뒤 등록한다', async ({
  page,
}) => {
  const s = await setup(page)
  await page.goto(fresh)
  await fill(page)
  await page
    .getByLabel('YouTube 채널 ID*', { exact: true })
    .fill('UC' + 'b'.repeat(22))
  await page
    .getByRole('button', { name: '채널 유형: 그룹', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '멤버', exact: true })
    .click()
  await page
    .getByRole('button', { name: '연결 멤버 선택', exact: true })
    .click()
  await page.getByLabel('이름·별칭 검색', { exact: true }).fill('테스트')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.getByRole('radio', { name: '테스트 멤버', exact: true }).check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await expect(page.getByText('테스트 멤버', { exact: true })).toBeVisible()
  await page
    .getByRole('button', { name: '수집 사용 여부: 사용 안 함', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '사용', exact: true })
    .click()
  await page.getByRole('button', { name: '채널 등록', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '채널을 등록했습니다', exact: true }),
  ).toBeVisible()
  expect(s.writes[0]).toMatchObject({
    memberId: mid,
    channelType: 'MEMBER',
    collectionEnabled: true,
    version: 0,
  })
})
