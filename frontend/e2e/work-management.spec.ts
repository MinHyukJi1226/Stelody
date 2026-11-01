import { test, expect, makeSong } from './fixtures'
import type { Page } from '@playwright/test'
import { fixture } from './original-artist-fixtures'
import { aid, wid } from '../src/test/admin-song'
import type { WorkInput } from '../src/features/admin/original-artists'

const original = '/admin/works?q=원곡&page=1&size=10'
const path = `/admin/works/${wid}/edit?returnTo=${encodeURIComponent(original)}`
const fresh = `/admin/works/new?returnTo=${encodeURIComponent(original)}`
test('관리 메뉴의 영상 연결은 대상 곡을 선택한 뒤 기존 연결 화면으로 진입한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto('/admin/works')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page
    .getByRole('button', { name: '영상 추가 연결', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '기존 곡 선택', exact: true }),
  ).toBeVisible()
  await page.getByRole('radio').first().check()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === `/admin/songs/${state.artists.song.song.id}/videos/new`,
  )
})
async function setup(page: Page) {
  const artists = await fixture(page)
  const state = {
    artists,
    work: structuredClone(artists.work),
    writes: [] as { method: string; body: WorkInput }[],
    mode: 'success',
    readError: 0,
    listError: false,
    gate: Promise.resolve(),
    duplicates: [] as string[],
    lists: [] as string[],
    gets: 0,
  }
  await page.route('**/api/v1/admin/works**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      suffix = url.pathname.replace('/api/v1/admin/works', '')
    if (req.method() === 'GET' && !suffix) {
      state.lists.push(url.search)
      return route.fulfill(
        state.listError
          ? { status: 503, json: { code: 'UNAVAILABLE' } }
          : {
              json: {
                page: Number(url.searchParams.get('page')),
                size: Number(url.searchParams.get('size')),
                hasNext: Number(url.searchParams.get('page')) === 0,
                items: [
                  {
                    id: wid,
                    name: state.work.title,
                    version: state.work.version,
                    status: null,
                  },
                ],
              },
            },
      )
    }
    if (req.method() === 'GET') {
      state.gets++
      return route.fulfill(
        state.readError
          ? {
              status: state.readError,
              json: {
                code:
                  state.readError === 403
                    ? 'ACCESS_DENIED'
                    : 'CATALOG_RESOURCE_NOT_FOUND',
              },
            }
          : { json: state.work },
      )
    }
    const body = req.postDataJSON() as WorkInput
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
              field: 'links[0].url',
              code: 'INVALID_VALUE',
              message: '공식 링크를 확인해주세요',
            },
          ],
        },
      })
    if (state.mode === 'conflict')
      return route.fulfill({
        status: 409,
        json: { code: 'CATALOG_VERSION_CONFLICT' },
      })
    state.work = {
      ...state.work,
      ...body,
      id: req.method() === 'POST' ? makeSong(900).id : state.work.id,
      version: req.method() === 'POST' ? 0 : body.version + 1,
      links: body.links.map((x, i) => ({
        ...x,
        id: state.work.links[i]?.id ?? makeSong(901 + i).id,
        checkedAt: state.work.links[i]?.checkedAt ?? '2026-10-30T00:00:00Z',
      })),
      karaoke: body.karaoke.map((x, i) => ({
        ...x,
        id: state.work.karaoke[i]?.id ?? makeSong(910 + i).id,
        number: x.number ?? null,
        sourceUrl: x.sourceUrl ?? null,
        checkedAt: state.work.karaoke[i]?.checkedAt ?? null,
      })),
    }
    artists.work = state.work
    if (state.mode === 'lost') return route.abort('failed')
    if (state.mode === 'malformed')
      return route.fulfill({ json: { item: state.work } })
    return route.fulfill({
      status: req.method() === 'POST' ? 201 : 200,
      json: { item: state.work, possibleDuplicateIds: state.duplicates },
    })
  })
  return state
}
async function fill(page: Page) {
  await page.getByLabel('원곡 제목*', { exact: true }).fill('수정한 원곡')
  await page.getByLabel('변경 사유*', { exact: true }).fill('공식 크레딧 확인')
}
async function save(page: Page) {
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '확인 후 저장', exact: true })
    .click()
}
test('관리 메뉴에서 원곡 목록에 진입하고 영상 정보 수정 메뉴는 표시하지 않는다', async ({
  page,
}) => {
  await setup(page)
  await page.goto('/admin')
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await expect(
    page.getByRole('button', {
      name: '영상 정보 수정 · 준비 중',
      exact: true,
    }),
  ).toHaveCount(0)
  await page.getByRole('link', { name: '원곡 관리', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '원곡 관리', exact: true }),
  ).toBeVisible()
  await page.getByLabel('검색', { exact: true }).fill('원곡')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page).toHaveURL(
    '/admin/works?q=%EC%9B%90%EA%B3%A1&page=1&size=20',
  )
})
test('전체 저장·목록 문맥 복귀·새로고침 유지, 변경하지 않은 근거를 보존한다', async ({
  page,
}) => {
  const state = await setup(page)
  const before = structuredClone(state.work)
  await page.goto(original)
  await page.getByRole('link', { name: '원곡 수정', exact: true }).click()
  await fill(page)
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]!.body).toMatchObject({
    title: '수정한 원곡',
    version: 8,
    aliases: before.aliases,
    artistIds: before.artistIds,
    reason: '공식 크레딧 확인',
  })
  expect(state.writes[0]!.body.links).toEqual(
    before.links.map(({ platform, url, sourceUrl }) => ({
      platform,
      url,
      sourceUrl,
    })),
  )
  expect(state.writes[0]!.body.links[0]).not.toHaveProperty('id')
  expect(state.writes[0]!.body.karaoke[0]).not.toHaveProperty('checkedAt')
  await page.getByRole('link', { name: '원곡 수정', exact: true }).click()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
  await page.reload()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
})
test('원곡 등록은 확인 후 한 번 실행하며 유사 원곡·등록한 항목을 안내한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.duplicates = [wid]
  await page.goto(fresh)
  await page.getByRole('button', { name: '원곡 등록', exact: true }).click()
  await expect(
    page
      .getByText('원곡 제목은 1–300자로 입력해주세요.', { exact: true })
      .first(),
  ).toBeVisible()
  expect(state.writes).toHaveLength(0)
  await fill(page)
  await page.getByRole('button', { name: '원곡 등록', exact: true }).click()
  expect(state.writes).toHaveLength(0)
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '등록', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '원곡을 등록했습니다', exact: true }),
  ).toBeVisible()
  expect(state.writes[0]).toMatchObject({
    method: 'POST',
    body: {
      version: 0,
      title: '수정한 원곡',
      artistIds: [],
      aliases: [],
      links: [],
    },
  })
  await expect(
    page.getByRole('link', { name: '유사 원곡 1 확인', exact: true }),
  ).toBeVisible()
  await page.getByRole('link', { name: '원래 목록으로', exact: true }).click()
  await expect(page).toHaveURL(original)
})
test('별칭·링크·노래방을 편집하고 필수 근거와 상태별 번호를 검증한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page
    .getByLabel('검색 별칭 · 선택', { exact: true })
    .fill('한국어 검색명')
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await page.getByRole('button', { name: '링크 1 삭제', exact: true }).click()
  await page.getByRole('button', { name: /^TJ 상태:/ }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '미등록 확인', exact: true })
    .click()
  await page
    .getByLabel('TJ 근거 URL*', { exact: true })
    .fill('http://example.com')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page
      .getByText('확인 근거의 HTTPS URL을 입력해주세요.', { exact: true })
      .first(),
  ).toBeVisible()
  expect(state.writes).toHaveLength(0)
  await page
    .getByLabel('TJ 근거 URL*', { exact: true })
    .fill('https://example.com/tj')
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[0]!.body).toMatchObject({
    aliases: ['원제', '한국어 검색명'],
    links: [],
    karaoke: [
      {
        provider: 'TJ',
        status: 'NOT_LISTED',
        sourceUrl: 'https://example.com/tj',
      },
    ],
  })
  expect(state.writes[0]!.body.karaoke[0]).not.toHaveProperty('number')
})
test('원곡 초안은 아티스트 별도 저장·선택 후에도 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '아티스트 선택', exact: true }).click()
  await page
    .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
    .click()
  await page
    .getByLabel('아티스트 이름*', { exact: true })
    .fill('새 아티스트 이름')
  await page
    .getByRole('dialog')
    .getByLabel('변경 사유*', { exact: true })
    .fill('표기 수정')
  await page
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await page
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await page.getByRole('button', { name: '선택 적용', exact: true }).click()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
  expect(state.artists.artistWrites).toHaveLength(1)
  expect(state.writes).toHaveLength(0)
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[0]!.body.artistIds).toEqual([aid])
})
test('버전 충돌은 입력·사유를 보존하고 최신 버전을 확인한 뒤 명시적으로 저장한다', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(path)
  await fill(page)
  state.mode = 'conflict'
  await save(page)
  await expect(
    page.getByRole('heading', {
      name: '다른 관리자가 먼저 변경했습니다',
      exact: true,
    }),
  ).toBeVisible()
  state.work = { ...state.work, title: '다른 관리자의 제목', version: 9 }
  state.mode = 'success'
  await page
    .getByRole('button', { name: '최신 저장값 조회', exact: true })
    .click()
  await expect(
    page.getByText('다른 관리자의 제목', { exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
    '공식 크레딧 확인',
  )
  await save(page)
  await expect(page).toHaveURL(original)
  expect(state.writes[1]!.body.version).toBe(9)
})
test('수정 응답 유실은 완료로 단정하거나 PUT를 자동 재전송하지 않는다', async ({
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
    page.getByText(
      /현재 값이 내 입력과 같아도 이 요청의 성공을 증명하지 않습니다/,
    ),
  ).toBeVisible()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '입력 재확인', exact: true }).click()
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인하지 않고 이동할까요?',
      exact: true,
    }),
  ).toBeVisible()
})
for (const mode of ['lost', 'malformed'])
  test(`신규 생성 ${mode} 후 자동 재등록을 막고 목록에서 확인한다`, async ({
    page,
  }) => {
    const state = await setup(page)
    state.mode = mode
    await page.goto(fresh)
    await fill(page)
    await page.getByRole('button', { name: '원곡 등록', exact: true }).click()
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
      page.getByRole('button', { name: '원곡 등록', exact: true }),
    ).toBeDisabled()
    expect(state.writes).toHaveLength(1)
    await page
      .getByRole('button', { name: '원곡 목록에서 확인', exact: true })
      .click()
    await page
      .getByRole('button', { name: '변경 버리고 이동', exact: true })
      .click()
    await expect(page).toHaveURL(
      (url) =>
        url.pathname === '/admin/works' &&
        url.searchParams.get('q') === '수정한 원곡',
    )
  })
test('미저장 메뉴 이동은 목적지를 보존하고 취소·확인을 구분한다', async ({
  page,
}) => {
  await setup(page)
  await page.goto(path)
  await fill(page)
  await page.getByRole('button', { name: '관리 기능', exact: true }).click()
  await page.getByRole('link', { name: '노래 수동 등록', exact: true }).click()
  await page.getByRole('button', { name: '계속 작성', exact: true }).click()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
  await page.getByRole('link', { name: '노래 목록', exact: true }).click()
  await page
    .getByRole('button', { name: '변경 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/admin/songs')
})
test('저장 중 입력·중복 실행·이동을 잠그고 성공 후 원래 목록으로 간다', async ({
  page,
}) => {
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
  await expect(
    page.getByRole('button', { name: '확인 후 저장', exact: true }),
  ).toBeDisabled()
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toBeDisabled()
  release()
  await expect(page).toHaveURL(original)
  expect(state.writes).toHaveLength(1)
})
test('목록 조회 실패는 요청 페이지를 유지하고 재조회한다', async ({ page }) => {
  const state = await setup(page)
  state.listError = true
  await page.goto(original)
  await expect(
    page.getByRole('heading', {
      name: '원곡 목록을 조회하지 못했습니다',
      exact: true,
    }),
  ).toBeVisible()
  await expect(page).toHaveURL(original)
  state.listError = false
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('link', { name: '원곡 수정', exact: true }),
  ).toBeVisible()
  expect(
    state.lists.every((x) => new URLSearchParams(x).get('page') === '1'),
  ).toBe(true)
})
for (const status of [404, 403])
  test(`상세 ${status}는 대상 없음·권한 없음으로 구분한다`, async ({
    page,
  }) => {
    const state = await setup(page)
    state.readError = status
    await page.goto(path)
    await expect(
      page.getByRole('heading', {
        name:
          status === 404 ? '원곡을 찾을 수 없습니다' : '관리자 권한이 없어요',
        exact: true,
      }),
    ).toBeVisible()
    expect(state.writes).toHaveLength(0)
  })
test('서버 필드 오류를 관련 입력 아래에 표시하고 초안을 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.mode = 'invalid'
  await page.goto(path)
  await fill(page)
  await save(page)
  await expect(page.getByLabel('링크 URL*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
    '수정한 원곡',
  )
  expect(state.writes).toHaveLength(1)
})
for (const editor of ['edit', 'create'] as const) {
  for (const action of ['home', 'account', 'back', 'logout'] as const) {
    test(`${editor} 저장 403 후 ${action} 이동 확인을 취소하거나 완료할 수 있다`, async ({
      page,
      isMobile,
    }) => {
      const state = await setup(page)
      let logouts = 0
      await page.route('**/api/v1/auth/logout', (route) => {
        logouts++
        state.artists.song.admin.loggedIn = false
        return route.fulfill({ status: 204 })
      })
      await page.goto(original)
      await page
        .getByRole('link', {
          name: editor === 'edit' ? '원곡 수정' : '원곡 등록',
          exact: true,
        })
        .click()
      await fill(page)
      state.mode = 'denied'
      if (editor === 'edit') await save(page)
      else {
        await page
          .getByRole('button', { name: '원곡 등록', exact: true })
          .click()
        await page
          .getByRole('dialog')
          .getByRole('button', { name: '등록', exact: true })
          .click()
      }
      await expect(
        page.getByRole('heading', {
          name: '관리자 권한이 없어요',
          exact: true,
        }),
      ).toBeVisible()
      const attempt = async () => {
        if (action === 'back') await page.evaluate(() => window.history.back())
        else if (action === 'logout') {
          if (isMobile)
            await page
              .getByRole('button', { name: '메뉴 열기', exact: true })
              .click()
          await page
            .getByRole('button', { name: '로그아웃', exact: true })
            .click()
        } else
          await page
            .getByRole('main')
            .getByRole('link', {
              name: action === 'home' ? '홈으로' : '계정 관리',
              exact: true,
            })
            .click()
      }
      await attempt()
      await expect(
        page.getByRole('heading', {
          name: '저장하지 않고 이동할까요?',
          exact: true,
        }),
      ).toBeVisible()
      expect(logouts).toBe(0)
      await page.getByRole('button', { name: '계속 작성', exact: true }).click()
      await expect(
        page.getByRole('heading', {
          name: '관리자 권한이 없어요',
          exact: true,
        }),
      ).toBeVisible()
      expect(state.writes).toHaveLength(1)
      await attempt()
      await page
        .getByRole('button', { name: '변경 버리고 이동', exact: true })
        .click()
      if (action === 'logout') {
        await expect.poll(() => logouts).toBe(1)
        await expect(
          page.getByRole('heading', { name: '로그인이 필요해요', exact: true }),
        ).toBeVisible()
      } else
        await expect(page).toHaveURL(
          action === 'back' ? original : action === 'home' ? '/' : '/account',
        )
      expect(state.writes).toHaveLength(1)
    })
  }
  test(`${editor} 세션 조회 장애 후 이동 확인과 재조회 시 입력 보존`, async ({
    page,
  }) => {
    const state = await setup(page)
    await page.goto(editor === 'edit' ? path : fresh)
    await fill(page)
    await page
      .getByLabel('검색 별칭 · 선택', { exact: true })
      .fill('입력 중 별칭')
    state.artists.song.admin.sessionError = 503
    await page.evaluate(() =>
      window.dispatchEvent(new Event('visibilitychange')),
    )
    await expect(
      page.getByRole('heading', {
        name: '권한을 확인하지 못했어요',
        exact: true,
      }),
    ).toBeVisible()
    await page.getByRole('link', { name: 'Stelody 홈', exact: true }).click()
    await expect(
      page.getByRole('heading', {
        name: '저장하지 않고 이동할까요?',
        exact: true,
      }),
    ).toBeVisible()
    await page.getByRole('button', { name: '계속 작성', exact: true }).click()
    state.artists.song.admin.sessionError = 0
    await page.getByRole('button', { name: '다시 조회', exact: true }).click()
    await expect(page.getByLabel('원곡 제목*', { exact: true })).toHaveValue(
      '수정한 원곡',
    )
    await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
      '공식 크레딧 확인',
    )
    await expect(
      page.getByLabel('검색 별칭 · 선택', { exact: true }),
    ).toHaveValue('입력 중 별칭')
    expect(state.writes).toHaveLength(0)
    state.artists.song.admin.sessionError = 503
    await page.evaluate(() =>
      window.dispatchEvent(new Event('visibilitychange')),
    )
    await expect(
      page.getByRole('heading', {
        name: '권한을 확인하지 못했어요',
        exact: true,
      }),
    ).toBeVisible()
    await page.getByRole('link', { name: 'Stelody 홈', exact: true }).click()
    await page
      .getByRole('button', { name: '변경 버리고 이동', exact: true })
      .click()
    await expect(page).toHaveURL('/')
    expect(state.writes).toHaveLength(0)
  })
}
test('라이트·다크 원곡 편집과 관리 메뉴는 화면 너비를 넘지 않는다', async ({
  page,
}, testInfo) => {
  await setup(page)
  await page.goto(path)
  await expect(page.getByLabel('원곡 제목*', { exact: true })).toBeVisible()
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark')
      await page
        .getByRole('button', { name: '다크 모드로 변경', exact: true })
        .click()
    await expect(page.locator('html')).not.toHaveCSS('overflow-x', 'scroll')
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    await page.screenshot({
      path: testInfo.outputPath(`work-${theme}.png`),
      fullPage: true,
    })
    await page.getByRole('button', { name: '관리 기능', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    expect(
      await page
        .getByRole('dialog')
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`menu-${theme}.png`) })
    await page.getByRole('button', { name: '닫기', exact: true }).click()
  }
})
