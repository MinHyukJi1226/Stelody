import { test, expect, makeSong } from './fixtures'
import { admin } from './admin-fixtures'
import type { SongInput } from '../src/features/admin/songs'
import { mockSongEditor, editPath, sid } from './admin-song-fixtures'

async function edit(page: import('@playwright/test').Page) {
  await page.goto(editPath)
  await page.getByLabel('곡명*', { exact: true }).fill('보존할 제목')
  await page.getByLabel('변경 사유*', { exact: true }).fill('보존할 변경 사유')
  await page
    .getByLabel('새 별칭', { exact: true })
    .fill('아직 추가하지 않은 별칭')
}

test('일시적인 세션 503 후 재확인해도 제목·변경 사유·별칭 입력과 최초 버전 유지', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await edit(page)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 저장', exact: true }),
  ).toHaveCount(0)
  expect(state.puts).toHaveLength(0)
  expect(
    await page.evaluate(() => {
      const event = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(event)
      return event.defaultPrevented
    }),
  ).toBe(true)
  state.admin.sessionError = 0
  state.song.title = '서버의 다른 제목'
  state.song.version = 6
  let release!: () => void
  state.admin.sessionGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  expect(state.puts).toHaveLength(0)
  release()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '보존할 제목',
  )
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
    '보존할 변경 사유',
  )
  await expect(page.getByLabel('새 별칭', { exact: true })).toHaveValue(
    '아직 추가하지 않은 별칭',
  )
  await page.getByLabel('새 별칭', { exact: true }).fill('')
  state.mode = 'conflict'
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '다른 변경사항이 있어요' }),
  ).toBeVisible()
  expect(state.puts[0]?.version).toBe(5)
})

for (const [button, empty, field] of [
  ['원곡 선택', '원곡 연결 안 함', 'workId'],
  ['대표 영상 선택', '대표 영상 지정 안 함', 'representativeVideoId'],
] as const)
  test(`${button}에서 기존 연결 해제는 초안에만 적용하고 저장 시 반영`, async ({
    page,
  }) => {
    const state = await mockSongEditor(page)
    await page.goto(editPath)
    await page.getByRole('button', { name: button, exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: empty, exact: true }).check()
    await dialog.getByRole('button', { name: '선택 적용', exact: true }).click()
    expect(state.puts).toHaveLength(0)
    expect(state.song[field]).not.toBeNull()
    await page.getByRole('button', { name: button, exact: true }).click()
    await expect(
      dialog.getByRole('radio', { name: empty, exact: true }),
    ).toBeChecked()
    await dialog.getByRole('button', { name: '취소', exact: true }).click()
    await page
      .getByLabel('변경 사유*', { exact: true })
      .fill('잘못된 연결 해제')
    await page.getByRole('button', { name: '변경 저장', exact: true }).click()
    await expect(page).toHaveURL('/admin/songs?q=노래&page=1&size=10')
    expect(state.puts).toHaveLength(1)
    expect(state.puts[0]).not.toHaveProperty(field)
    expect(state.song[field]).toBeNull()
  })

test('일시적인 네트워크 오류 뒤에는 원래 탭과 적용한 선택도 유지', async ({
  page,
}) => {
  await mockSongEditor(page)
  await edit(page)
  await page.getByLabel('새 별칭', { exact: true }).fill('')
  await page
    .getByRole('button', { name: '외부 아티스트 선택', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('checkbox', { name: '원곡 아티스트' })
    .check()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '선택 적용' })
    .click()
  await page.getByRole('tab', { name: '공개·검색' }).click()
  await page.getByRole('checkbox', { name: '특별 목적 곡' }).check()
  await page.getByLabel('특별 목적 표시 문구*').fill('보존할 기념 문구')
  let offline = true
  await page.route('**/api/v1/me', (route) =>
    offline ? route.abort('failed') : route.fallback(),
  )
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  offline = false
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByRole('tab', { name: '공개·검색' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByLabel('특별 목적 표시 문구*')).toHaveValue(
    '보존할 기념 문구',
  )
  await page.getByRole('tab', { name: '기본 정보', exact: true }).click()
  await page
    .getByRole('button', { name: '외부 아티스트 선택', exact: true })
    .click()
  await expect(
    page.getByRole('dialog').getByRole('checkbox', { name: '원곡 아티스트' }),
  ).toBeChecked()
})

test('세션 오류 중에도 미저장 이동 확인을 거쳐 나갈 수 있음', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await edit(page)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await page.getByRole('button', { name: '계속 수정', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Stelody 홈' }).click()
  await page
    .getByRole('button', { name: '변경사항 버리고 이동', exact: true })
    .click()
  await expect(page).toHaveURL('/')
  expect(state.puts).toHaveLength(0)
})

test('재확인 결과 역할이 바뀌면 보존한 관리자 초안을 노출하지 않음', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await edit(page)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  state.admin.role = 'USER'
  state.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveCount(0)
  await expect(page.getByText('보존할 제목', { exact: true })).toHaveCount(0)
  expect(state.puts).toHaveLength(0)
})

test('CSRF 대기 중 세션 확인이 실패하면 PUT를 보내지 않고 입력과 미확정 상태 보존', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await edit(page)
  await page.getByLabel('새 별칭', { exact: true }).fill('')
  let release!: () => void,
    started = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/auth/csrf', async (route) => {
    started = true
    await gate
    return route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'test' },
    })
  })
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  const csrfResponse = page.waitForResponse('**/api/v1/auth/csrf')
  release()
  await csrfResponse
  state.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '저장 결과를 확인해야 해요' }),
  ).toBeVisible()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '보존할 제목',
  )
  await expect(
    page.getByText('곡 정보를 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
  expect(state.puts).toHaveLength(0)
})

test('이미 전송한 저장 중 세션 확인 실패는 성공·재전송으로 처리하지 않음', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  await edit(page)
  await page.getByLabel('새 별칭', { exact: true }).fill('')
  let release!: () => void
  state.saveGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect.poll(() => state.puts.length).toBe(1)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  release()
  await expect.poll(() => state.song.version).toBe(6)
  state.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      name: '저장 결과를 확인해야 해요',
    }),
  ).toBeVisible()
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
    '보존할 변경 사유',
  )
  await page
    .getByRole('button', { name: '현재 저장값 확인', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '보존한 입력과 최신 값 비교' }),
  ).toBeVisible()
  expect(state.puts).toHaveLength(1)
  await expect(
    page.getByText('곡 정보를 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
})

test('세션 재확인에서 계정이 바뀌면 이전 계정의 초안을 복원하지 않음', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  let switched = false
  await page.route('**/api/v1/me', (route) =>
    switched
      ? route.fulfill({ json: { ...admin, id: makeSong(998).id } })
      : route.fallback(),
  )
  await edit(page)
  state.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  switched = true
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '노래 제목',
  )
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('새 별칭', { exact: true })).toHaveValue('')
  expect(state.puts).toHaveLength(0)
})

test('원곡 연결 해제를 취소하면 기존 선택을 유지', async ({ page }) => {
  const state = await mockSongEditor(page)
  await page.goto(editPath)
  await page.getByRole('button', { name: '원곡 선택', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('radio', { name: '원곡 연결 안 함', exact: true })
    .check()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '취소', exact: true })
    .click()
  await page.getByRole('button', { name: '원곡 선택', exact: true }).click()
  await expect(
    page
      .getByRole('dialog')
      .getByRole('radio', { name: '원곡 제목', exact: true }),
  ).toBeChecked()
  expect(state.puts).toHaveLength(0)
})

test('공개 곡의 대표 영상 해제는 서버 검증 오류를 표시하고 초안을 유지', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  state.song.visibility = 'PUBLISHED'
  await page.route(`**/api/v1/admin/songs/${sid}`, (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    state.puts.push(route.request().postDataJSON() as SongInput)
    return route.fulfill({
      status: 400,
      json: {
        code: 'SONG_NOT_PUBLISHABLE',
        title: '공개 곡에는 대표 영상이 필요합니다.',
        fieldErrors: [
          {
            field: 'representativeVideoId',
            code: 'REQUIRED',
            message: '대표 영상을 선택해주세요.',
          },
        ],
      },
    })
  })
  await page.goto(editPath)
  await page
    .getByRole('button', { name: '대표 영상 선택', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .getByRole('radio', { name: '대표 영상 지정 안 함', exact: true })
    .check()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '선택 적용', exact: true })
    .click()
  await page
    .getByLabel('변경 사유*', { exact: true })
    .fill('대표 영상 연결 검증')
  await page.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(
    page.getByText('대표 영상을 선택해주세요.', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('곡 정보를 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
  await page
    .getByRole('button', { name: '대표 영상 선택', exact: true })
    .click()
  await expect(
    page
      .getByRole('dialog')
      .getByRole('radio', { name: '대표 영상 지정 안 함', exact: true }),
  ).toBeChecked()
  expect(state.puts[0]?.visibility).toBe('PUBLISHED')
  expect(state.song.representativeVideoId).not.toBeNull()
  expect(state.puts).toHaveLength(1)
})

test('원곡 선택 목록 조회 실패 중에도 명시적인 연결 해제는 적용 가능', async ({
  page,
}) => {
  const state = await mockSongEditor(page)
  state.selectorError = true
  await page.goto(editPath)
  await page.getByRole('button', { name: '원곡 선택', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('목록을 조회하지 못했어요.')).toBeVisible()
  await dialog
    .getByRole('radio', { name: '원곡 연결 안 함', exact: true })
    .check()
  await dialog.getByRole('button', { name: '선택 적용', exact: true }).click()
  await expect(
    page.getByText('연결된 원곡 없음', { exact: true }),
  ).toBeVisible()
  expect(state.puts).toHaveLength(0)
})
