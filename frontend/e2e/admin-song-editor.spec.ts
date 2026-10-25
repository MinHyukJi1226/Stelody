import { test, expect } from './fixtures'
import { mockSongEditor, editPath } from './admin-song-fixtures'
import { sid, mid, wid, vid } from '../src/test/admin-song'

async function openEditor(page: import('@playwright/test').Page) {
  await page.goto(editPath)
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '노래 제목',
  )
}
async function fillSave(page: import('@playwright/test').Page) {
  await page.getByLabel('곡명*', { exact: true }).fill('내 수정 제목')
  await page.getByLabel('변경 사유*', { exact: true }).fill('정보 확인 후 수정')
}
test('곡 목록 검색·다음 실패·재조회·편집 복귀 문맥', async ({ page }) => {
  const state = await mockSongEditor(page)
  await page.goto('/admin/songs?q=노래&page=0&size=10')
  await expect(page.getByText('멤버 · 미확정', { exact: true })).toBeVisible()
  await expect(page.getByText('발견 기록 없음', { exact: true })).toBeVisible()
  await expect(page.getByText('완성', { exact: true })).toBeVisible()
  state.listError = true
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '조회하지 못했어요' }),
  ).toBeVisible()
  expect(new URL(page.url()).searchParams.get('page')).toBe('1')
  state.listError = false
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('button', { name: '다음', exact: true }),
  ).toBeDisabled()
  await page.getByRole('link', { name: '수정', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '노래 제목',
  )
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL('/admin/songs?q=노래&page=1&size=10')
})
test('정보 보완에서 곡 ID로 열고 원래 페이지로 저장 복귀', async ({ page }) => {
  const state = await mockSongEditor(page)
  await page.route(
    '**/api/v1/admin/cover-auto-publication/registrations?*',
    (route) =>
      route.fulfill({
        json: {
          items: [
            {
              reviewId: mid,
              songId: sid,
              title: '정보 보완 곡',
              visibility: 'HIDDEN',
              processedAt: '2026-10-24T03:00:00Z',
              missingFields: ['KY'],
            },
          ],
          page: 1,
          size: 20,
          hasNext: false,
        },
      }),
  )
  await page.goto('/admin/registrations?page=1')
  await page.getByRole('link', { name: '수정', exact: true }).click()
  await fillSave(page)
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page).toHaveURL('/admin/registrations?page=1')
  expect(state.puts).toHaveLength(1)
})
test('세 탭·테마 전환 후 초안과 전체 입력 보존 및 확정 응답 후 복귀', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  await page.getByLabel('새 별칭', { exact: true }).fill('추가 별칭')
  await page.getByRole('button', { name: '별칭 추가', exact: true }).click()
  await page.getByRole('tab', { name: '공개·검색' }).click()
  await page.getByRole('checkbox', { name: '특별 목적 곡' }).check()
  await page.getByLabel('특별 목적 표시 문구*').fill('생일 기념')
  await page.getByRole('button', { name: /모드로 변경/ }).click()
  await page.getByRole('tab', { name: '링크·노래방' }).click()
  await expect(page.getByLabel('TJ 번호*')).toHaveValue('12345')
  await page.getByRole('tab', { name: '기본 정보', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page).toHaveURL('/admin/songs?q=노래&page=1&size=10')
  await expect(
    page.getByText('곡 정보를 저장했습니다.', { exact: true }),
  ).toBeVisible()
  expect(state.puts).toHaveLength(1)
  expect(state.puts[0]).toMatchObject({
    version: 5,
    title: '내 수정 제목',
    aliases: ['검색 별칭', '추가 별칭'],
    memberIds: [mid],
    workId: wid,
    representativeVideoId: vid,
    isSpecialEvent: true,
    specialEventLabel: '생일 기념',
    links: [{ platform: 'YouTube Music' }],
    karaoke: [
      { provider: 'TJ', number: '12345' },
      { provider: 'KY', status: 'UNKNOWN' },
    ],
  })
  expect(state.puts[0]).not.toHaveProperty('videos')
  expect(state.puts[0]).not.toHaveProperty('searchCheckedAt')
})
test('선택 Dialog 취소는 초안에 미적용, 적용은 저장 전까지 미전송', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('checkbox', { name: '참여 멤버' }).uncheck()
  await dialog.getByRole('button', { name: '취소', exact: true }).click()
  await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
  await expect(
    dialog.getByRole('checkbox', { name: '참여 멤버' }),
  ).toBeChecked()
  await dialog.getByRole('checkbox', { name: '참여 멤버' }).uncheck()
  await dialog.getByRole('button', { name: '다음', exact: true }).click()
  await expect(dialog.getByText('2페이지', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '선택 적용', exact: true }).click()
  expect(state.puts).toHaveLength(0)
  await page.getByLabel('변경 사유*').fill('참여자 확인')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect.poll(() => state.puts.length).toBe(1)
  expect(state.puts[0]?.memberIds).toEqual([])
})
test('미저장 헤더 이동은 목적지 보존·계속 수정·버리고 이동', async ({
  page,
}) => {
  await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '계속 수정', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await page
    .getByRole('button', { name: '변경사항 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/')
})
test('검증 실패는 초안 유지, 서버 필드 오류는 해당 탭 아래 표시', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  await page.getByRole('tab', { name: '공개·검색' }).click()
  await page.getByRole('button', { name: '검색 어려움', exact: true }).click()
  await page.getByLabel('권장 검색어*').fill('')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page.getByLabel('권장 검색어*')).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(state.puts).toHaveLength(0)
  await page.getByLabel('권장 검색어*').fill('검색어')
  state.mode = 'validation'
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await page.getByRole('tab', { name: '기본 정보', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await page.getByRole('tab', { name: '링크·노래방' }).click()
  await expect(page.getByLabel('링크 URL*', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
})
test('저장 중 입력·중복 실행·이동 잠금 후 실제 성공에만 복귀', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  let release!: () => void
  state.saveGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await openEditor(page)
  await fillSave(page)
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '저장 중…', exact: true }),
  ).toBeDisabled()
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경사항 버리고 이동' }),
  ).toBeDisabled()
  expect(state.puts).toHaveLength(1)
  release()
  await expect(page).toHaveURL('/admin/songs?q=노래&page=1&size=10')
})
test('버전 충돌은 B·D·S 비교와 명시적 최종 확인 후 최신 버전 저장', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  state.mode = 'conflict'
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  state.song.title = '다른 관리자의 제목'
  state.song.version = 6
  await page.getByRole('button', { name: '현재 저장값 확인' }).click()
  await expect(
    page.getByRole('heading', { name: '보존한 입력과 최신 값 비교' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '최종 값 검토' }),
  ).toBeDisabled()
  await page.getByRole('button', { name: '내 입력 사용', exact: true }).click()
  await page.getByRole('button', { name: '최종 값 검토' }).click()
  await expect(
    page.getByRole('button', { name: '재확인 후 저장' }),
  ).toBeDisabled()
  expect(state.puts).toHaveLength(1)
  state.mode = 'success'
  await page
    .getByRole('checkbox', { name: '최종 값을 모두 확인했습니다' })
    .check()
  await page.getByRole('button', { name: '재확인 후 저장' }).click()
  await expect(page).toHaveURL('/admin/songs?q=노래&page=1&size=10')
  expect(state.puts[1]?.version).toBe(6)
  expect(state.puts[1]?.title).toBe('내 수정 제목')
})
for (const mode of ['lost', 'malformed'] as const)
  test(`저장 응답 ${mode}는 동일 값 조회 후에도 자동 성공·재전송 없음`, async ({
    page,
  }) => {
    const state = await mockSongEditor(page)
    await openEditor(page)
    await fillSave(page)
    state.mode = mode
    await page.getByRole('button', { name: '변경 저장', exact: true }).click()
    await expect(
      page.getByRole('heading', { name: '저장 결과를 확인해야 해요' }),
    ).toBeVisible()
    await expect(
      page.getByText('곡 정보를 저장했습니다.', { exact: true }),
    ).toHaveCount(0)
    await page.getByRole('button', { name: '현재 저장값 확인' }).click()
    await expect(
      page.getByText(/앞선 요청의 성공을 증명하지 않습니다/),
    ).toBeVisible()
    expect(state.puts).toHaveLength(1)
    expect(state.song.title).toBe('내 수정 제목')
    await page.getByRole('link', { name: 'Stelody 홈' }).click()
    await expect(
      page.getByRole('heading', {
        name: '저장 결과를 확인하지 않고 나갈까요?',
      }),
    ).toBeVisible()
  })
test('포커스 권한 재조회가 편집 초안을 지우지 않음', async ({ page }) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  const calls = state.meCalls
  let release!: () => void
  state.admin.sessionGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.evaluate(() => {
    window.dispatchEvent(new Event('blur'))
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('visibilitychange'))
  })
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
  await expect.poll(() => state.meCalls).toBeGreaterThan(calls)
  release()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
})
for (const status of [401, 403, 404, 503])
  test(`곡 상세 ${status}는 전용 상태 표시`, async ({ page }) => {
    const state = await mockSongEditor(page)
    state.detailError = status
    await page.goto(editPath)
    await expect(
      page.getByRole('heading', {
        name:
          status === 401
            ? '로그인이 필요해요'
            : status === 403
              ? '관리자 권한이 없어요'
              : status === 404
                ? '대상 곡이 없습니다.'
                : '곡 정보를 조회하지 못했어요',
      }),
    ).toBeVisible()
    expect(state.puts).toHaveLength(0)
  })

test('미저장 로그아웃도 공통 확인 후 실행하고 저장 중에는 잠근다', async ({
  page,
  isMobile,
}) => {
  await mockSongEditor(page)
  let logouts = 0
  await page.route('**/api/v1/auth/logout', (route) => {
    logouts++
    return route.fulfill({ status: 204 })
  })
  await openEditor(page)
  await fillSave(page)
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '변경사항을 버리고 이동할까요?' }),
  ).toBeVisible()
  expect(logouts).toBe(0)
  await page.getByRole('button', { name: '계속 수정', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '내 수정 제목',
  )
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await page
    .getByRole('button', { name: '변경사항 버리고 이동', exact: true })
    .click()
  await expect.poll(() => logouts).toBe(1)
  await expect(
    page.getByRole('heading', { name: '로그인이 필요해요' }),
  ).toBeVisible()
})
test('CSRF 대기 중 관리자 권한 소실 시 PUT 전송 취소', async ({ page }) => {
  const state = await mockSongEditor(page)
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let csrfStarted = false
  await page.route('**/api/v1/auth/csrf', async (route) => {
    csrfStarted = true
    await gate
    return route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'test' },
    })
  })
  await openEditor(page)
  await fillSave(page)
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect.poll(() => csrfStarted).toBe(true)
  state.admin.role = 'USER'
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  release()
  // Wait until the held CSRF response is delivered; an aborted write must never follow it.
  await expect
    .poll(async () =>
      page.getByRole('heading', { name: '관리자 권한이 없어요' }).isVisible(),
    )
    .toBe(true)
  expect(state.puts).toHaveLength(0)
})
test('이미 전송한 저장의 늦은 성공이 로그아웃 후 성공 안내·복귀를 하지 않음', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  let release!: () => void
  state.saveGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await openEditor(page)
  await fillSave(page)
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect.poll(() => state.puts.length).toBe(1)
  state.admin.loggedIn = false
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '로그인이 필요해요' }),
  ).toBeVisible()
  release()
  await expect(
    page.getByText('곡 정보를 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
  await expect(page).toHaveURL(editPath)
})
test('양쪽 테마의 편집 탭·선택창·충돌 비교 화면 가로 넘침 없음', async ({
  page,
}, info) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark')
      await page.getByRole('button', { name: '다크 모드로 변경' }).click()
    for (const tab of ['기본 정보', '공개·검색', '링크·노래방']) {
      await page.getByRole('tab', { name: tab, exact: true }).click()
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true)
      await page.screenshot({
        path: info.outputPath(`${theme}-${tab}.png`),
        fullPage: true,
        animations: 'disabled',
      })
    }
    await page.getByRole('tab', { name: '기본 정보', exact: true }).click()
    await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.screenshot({ path: info.outputPath(`${theme}-selector.png`) })
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '취소', exact: true })
      .click()
  }
  await fillSave(page)
  state.mode = 'conflict'
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  state.song.title = '다른 관리자 제목'
  state.song.version = 6
  await page
    .getByRole('button', { name: '현재 저장값 확인', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '보존한 입력과 최신 값 비교' }),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.screenshot({
    path: info.outputPath('compare.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('검색 기록 뒤로 이동 시 입력창과 URL 검색어 일치', async ({ page }) => {
  await mockSongEditor(page)
  await page.goto('/admin/songs?q=첫검색&page=0&size=10')
  await expect(
    page.getByLabel('곡 제목·검색 별칭', { exact: true }),
  ).toHaveValue('첫검색')
  await page.getByLabel('곡 제목·검색 별칭', { exact: true }).fill('다음검색')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await expect(page).toHaveURL('/admin/songs?q=다음검색&page=0&size=10')
  await page.goBack()
  await expect(
    page.getByLabel('곡 제목·검색 별칭', { exact: true }),
  ).toHaveValue('첫검색')
})
test('선택 목록 권한 오류도 관리자 공통 화면으로 즉시 전환', async ({
  page,
}) => {
  await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  await page.route('**/api/v1/admin/members?*', (route) =>
    route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } }),
  )
  await page.getByRole('button', { name: '멤버 선택', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: '홈으로', exact: true }).click()
  await expect(page).toHaveURL('/')
})

test('관리자 캐시가 있어도 새 진입에서는 최신 권한 확인 전 관리자 API 요청 없음', async ({
  page,
  isMobile,
}) => {
  const state = await mockSongEditor(page)
  await page.goto('/account')
  await expect(
    page.getByRole('heading', { name: '계정 관리', exact: true }),
  ).toBeVisible()
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await expect(
    page.getByRole('link', {
      name: isMobile ? '관리자 작업함' : '관리자',
      exact: true,
    }),
  ).toBeVisible()
  let release!: () => void
  state.admin.sessionGate = new Promise<void>((resolve) => {
    release = resolve
  })
  const calls = state.gets.length
  await page
    .getByRole('link', {
      name: isMobile ? '관리자 작업함' : '관리자',
      exact: true,
    })
    .click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한을 확인하고 있어요' }),
  ).toBeVisible()
  expect(state.gets.length).toBe(calls)
  release()
  await expect(
    page.getByRole('heading', { name: '관리자 작업함', exact: true }),
  ).toBeVisible()
})

test('응답 유실 후 최신 값 조회가 지연돼도 확인 후 나가기는 가능', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await openEditor(page)
  await fillSave(page)
  state.mode = 'lost'
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장 결과를 확인해야 해요' }),
  ).toBeVisible()
  let release!: () => void
  state.detailGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.getByRole('button', { name: '현재 저장값 확인' }).click()
  await expect(
    page.getByRole('button', { name: '최신 값 조회 중…' }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await expect(
    page.getByRole('heading', { name: '저장 결과를 확인하지 않고 나갈까요?' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '변경사항 버리고 이동' }).click()
  await expect(page).toHaveURL('/')
  release()
})
