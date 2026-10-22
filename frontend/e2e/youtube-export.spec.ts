import type { Page } from '@playwright/test'
import { test, expect, makeSong, emptyPage } from './fixtures'
import type {
  ExportJob,
  ExportRequest,
} from '../src/features/youtube-export/api'
import type { YouTubeConnection } from '../src/features/youtube/api'

const id = makeSong(900).id
const jobId = makeSong(901).id
const path = `/library/playlists/${id}/youtube-export`
const jobPath = `${path}?jobId=${jobId}`
const user = {
  id: makeSong(800).id,
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const initial: ExportJob = {
  id: jobId,
  playlistId: id,
  version: 0,
  status: 'QUEUED',
  totalCount: 3,
  copiedCount: 0,
  skippedCount: 0,
  remainingCount: 3,
  youtubePlaylistId: null,
  youtubeUrl: null,
  errorCode: null,
  createdAt: '2026-10-22T01:00:00Z',
  updatedAt: '2026-10-22T01:00:00Z',
}
async function setup(page: Page) {
  const state = {
    connection: 'CONNECTED' as YouTubeConnection['status'],
    source: {
      id,
      name: '밤에 듣는 목소리',
      version: 0,
      totalCount: 3,
      availableCount: 2,
      containsSong: null,
      createdAt: initial.createdAt,
      updatedAt: initial.updatedAt,
    },
    job: structuredClone(initial),
    active: null as ExportJob | null,
    activeReads: 0,
    activeFailure: 0,
    activeGate: Promise.resolve(),
    sourceReads: [] as string[],
    created: [] as ExportRequest[],
    changed: [] as string[],
    authorizations: [] as { returnTo: string }[],
    reads: 0,
    loggedIn: true,
    sourceFailure: 0,
    readFailure: 0,
    createFailure: null as null | { status: number; code: string },
    authorizationFailure: null as null | { status: number; code: string },
    loseResponse: false,
    writeGate: Promise.resolve(),
    readGate: Promise.resolve(),
  }
  await page.route('**/api/v1/me', (r) =>
    r.fulfill(
      state.loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'export-test' } }),
  )
  await page.route('**/api/v1/auth/logout', (r) => {
    state.loggedIn = false
    return r.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/me/youtube/connection', (r) =>
    r.fulfill({ json: { status: state.connection } }),
  )
  await page.route('**/api/v1/me/youtube/authorizations', (r) => {
    state.authorizations.push(r.request().postDataJSON())
    if (state.authorizationFailure) {
      if (state.authorizationFailure.status === 401) state.loggedIn = false
      return r.fulfill({
        status: state.authorizationFailure.status,
        json: state.authorizationFailure,
      })
    }
    return r.fulfill({
      json: {
        authorizationUrl:
          'https://accounts.google.com/o/oauth2/v2/auth?state=test',
        expiresAt: '2100-01-01T00:00:00Z',
      },
    })
  })
  await page.route('https://accounts.google.com/o/oauth2/**', (r) =>
    r.fulfill({ contentType: 'text/html', body: '<h1>Test consent</h1>' }),
  )
  await page.route('**/api/v1/me/playlists**', async (r) => {
    const request = r.request(),
      url = new URL(request.url())
    if (request.method() === 'POST') {
      expect(url.pathname).toBe(`/api/v1/me/playlists/${id}/youtube-exports`)
      expect(request.headers()['x-csrf-token']).toBe('export-test')
      const body = request.postDataJSON() as ExportRequest
      state.created.push(body)
      await state.writeGate
      if (state.createFailure)
        return r.fulfill({
          status: state.createFailure.status,
          json: state.createFailure,
        })
      state.job.version = body.version
      state.active = ['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'].includes(
        state.job.status,
      )
        ? structuredClone(state.job)
        : null
      return state.loseResponse
        ? r.abort('failed')
        : r.fulfill({ status: 202, json: state.job })
    }
    if (state.sourceFailure)
      return r.fulfill({
        status: state.sourceFailure,
        json: { code: 'UNAVAILABLE' },
      })
    state.sourceReads.push(url.pathname)
    if (url.pathname.endsWith('/items'))
      return r.fulfill({
        json: {
          ...state.source,
          ...emptyPage,
          totalCount: 3,
          items: [1, 2, 3].map((n, position) => ({
            id: makeSong(950 + n).id,
            songId: makeSong(n).id,
            song: n === 3 ? null : makeSong(n),
            available: n !== 3,
            unavailableMessage: n === 3 ? '이용 불가' : null,
            position,
            addedAt: initial.createdAt,
          })),
        },
      })
    const requestedId = url.pathname.split('/')[5]
    return r.fulfill({
      json:
        requestedId === state.source.id
          ? state.source
          : { ...state.source, id: requestedId, name: '다른 플레이리스트' },
    })
  })
  await page.route('**/api/v1/me/youtube-exports/**', async (r) => {
    const request = r.request()
    if (request.method() === 'GET') {
      if (new URL(request.url()).pathname.endsWith('/active')) {
        state.activeReads++
        await state.activeGate
        if (state.activeFailure === 401) state.loggedIn = false
        return r.fulfill(
          state.activeFailure
            ? {
                status: state.activeFailure,
                json: { code: 'EXPORT_LOOKUP_UNAVAILABLE' },
              }
            : { json: { job: state.active } },
        )
      }
      state.reads++
      await state.readGate
      if (state.readFailure === 401) state.loggedIn = false
      return r.fulfill(
        state.readFailure
          ? { status: state.readFailure, json: { code: 'UNAVAILABLE' } }
          : { json: state.job },
      )
    }
    expect(request.headers()['x-csrf-token']).toBe('export-test')
    const kind = new URL(request.url()).pathname.split('/').at(-1)!
    state.changed.push(kind)
    await state.writeGate
    if (kind === 'retry') state.job.status = 'QUEUED'
    else if (state.job.status !== 'SUCCEEDED') state.job.status = 'CANCELLED'
    state.active = ['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'].includes(
      state.job.status,
    )
      ? structuredClone(state.job)
      : null
    return r.fulfill({ json: state.job })
  })
  return state
}
const panel = (page: Page) =>
  page.getByRole('region', { name: 'YouTube 내보내기 상태', exact: true })
const start = (page: Page) =>
  page.getByRole('button', { name: '내보내기 시작', exact: true })
const retry = (page: Page) =>
  page.getByRole('button', { name: '같은 작업 재시도', exact: true })
async function open(page: Page, url = path) {
  await page.goto(url)
  await expect(panel(page)).toBeVisible()
}

for (const status of ['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'] as const)
  test(`다른 탭에서 시작한 ${status} 작업을 재접수 없이 연다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.job.status = status
    s.active = structuredClone(s.job)
    s.source.version = 9
    await open(page)
    await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
    await expect(panel(page).locator('dd').first()).toHaveText('3곡')
    expect(s.activeReads).toBeGreaterThan(0)
    expect(s.created).toHaveLength(0)
    expect(s.changed).toHaveLength(0)
  })

test('다른 목록의 활성 작업은 그 원본 목록 화면에서 연다', async ({ page }) => {
  const s = await setup(page),
    other = makeSong(910).id
  s.job.playlistId = other
  s.active = structuredClone(s.job)
  await open(page)
  await expect(page).toHaveURL(
    new RegExp(`/playlists/${other}/youtube-export\\?jobId=${jobId}$`),
  )
  await expect(
    page.getByRole('heading', { name: '다른 플레이리스트', exact: true }),
  ).toBeVisible()
  expect(s.sourceReads).toContain(`/api/v1/me/playlists/${other}`)
  expect(s.created).toHaveLength(0)
})

for (const connection of ['DISABLED', 'RECONNECT_REQUIRED'] as const)
  test(`${connection}에서도 기존 실패 작업 조회와 취소를 제공한다`, async ({
    page,
  }) => {
    const s = await setup(page)
    s.connection = connection
    s.job.status = 'FAILED'
    s.active = structuredClone(s.job)
    await open(page)
    await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
    await expect(retry(page)).toBeDisabled()
    await expect(
      page.getByRole('button', { name: '취소', exact: true }),
    ).toBeEnabled()
    expect(s.created).toHaveLength(0)
  })

test('명시적으로 연 완료 작업을 다른 활성 작업으로 바꾸지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.active = { ...s.job, id: makeSong(911).id, playlistId: makeSong(910).id }
  s.job = { ...s.job, status: 'SUCCEEDED', copiedCount: 3, remainingCount: 0 }
  await open(page, jobPath)
  await expect(page.getByText('완료', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.activeReads).toBe(0)
  expect(s.created).toHaveLength(0)
})

test('활성 작업 조회 오류는 작업 없음으로 취급하지 않고 읽기만 다시 시도한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.activeFailure = 503
  await open(page)
  await expect(page.getByText('상태 조회 오류', { exact: true })).toBeVisible()
  await expect(start(page)).toHaveCount(0)
  await expect(panel(page).getByText('0곡', { exact: true })).toHaveCount(0)
  expect(s.created).toHaveLength(0)
  s.activeFailure = 0
  s.active = structuredClone(s.job)
  await page.getByRole('button', { name: '상태 다시 조회' }).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created).toHaveLength(0)
})

test('접수 직전 다른 탭에서 시작한 작업은 409 후 기존 작업 조회로 복구한다', async ({
  page,
}) => {
  const s = await setup(page),
    other = makeSong(910).id
  await open(page)
  await expect(start(page)).toBeEnabled()
  s.job.playlistId = other
  s.active = structuredClone(s.job)
  s.createFailure = { status: 409, code: 'EXPORT_ALREADY_ACTIVE' }
  await start(page).click()
  await expect(page).toHaveURL(
    new RegExp(`/playlists/${other}/youtube-export\\?jobId=${jobId}$`),
  )
  expect(s.created).toHaveLength(1)
  expect(s.changed).toHaveLength(0)
})

test('409 후 기존 작업 조회에 실패하면 새 요청을 보내지 않고 다시 조회한다', async ({
  page,
}) => {
  const s = await setup(page)
  await open(page)
  await expect(start(page)).toBeEnabled()
  s.createFailure = { status: 409, code: 'EXPORT_ALREADY_ACTIVE' }
  s.activeFailure = 503
  await start(page).click()
  await expect(page.getByText('상태 조회 오류', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/requestId=/)
  expect(s.created).toHaveLength(1)
  s.activeFailure = 0
  s.active = structuredClone(s.job)
  await page.getByRole('button', { name: '상태 다시 조회' }).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created).toHaveLength(1)
})

test('409 후 활성 작업이 이미 종료됐으면 준비 화면에서 다시 확인한다', async ({
  page,
}) => {
  const s = await setup(page)
  await open(page)
  await expect(start(page)).toBeEnabled()
  s.createFailure = { status: 409, code: 'EXPORT_ALREADY_ACTIVE' }
  await start(page).click()
  await expect(start(page)).toBeEnabled()
  await expect(page).toHaveURL(new RegExp(`${path}$`))
  expect(s.created).toHaveLength(1)
  s.createFailure = null
  await start(page).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created).toHaveLength(2)
  expect(s.created[1]?.requestId).not.toBe(s.created[0]?.requestId)
})

test('접수 응답 유실은 활성 작업 조회로 복구하며 POST를 반복하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.loseResponse = true
  await open(page)
  await start(page).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created).toHaveLength(1)
  expect(s.activeReads).toBeGreaterThan(1)
})

test('활성 작업 조회가 늦게 도착해도 떠나는 화면을 되돌리지 않는다', async ({
  page,
  isMobile,
}) => {
  const s = await setup(page)
  let release!: () => void
  s.activeGate = new Promise<void>((resolve) => (release = resolve))
  s.active = structuredClone(s.job)
  await open(page)
  await expect.poll(() => s.activeReads).toBe(1)
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('link', { name: '검색', exact: true }).first().click()
  release()
  await expect(page).toHaveURL(/\/search$/)
  await expect(page.getByRole('heading', { name: '노래 검색' })).toBeVisible()
  expect(s.created).toHaveLength(0)
})

test('활성 작업 조회 401은 로그인으로 이동하며 접수하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.activeFailure = 401
  await page.goto(path)
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect(panel(page)).toHaveCount(0)
  expect(s.created).toHaveLength(0)
})

test('상세의 내보내기 진입과 순서 편집 잠금을 유지한다', async ({ page }) => {
  await setup(page)
  await page.goto(`/library/playlists/${id}`)
  const button = page.getByRole('button', {
    name: 'YouTube로 내보내기',
    exact: true,
  })
  await expect(button).toBeEnabled()
  await page
    .getByRole('button', { name: /아래로/ })
    .first()
    .click()
  await expect(button).toBeDisabled()
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await button.click()
  await expect(page).toHaveURL(new RegExp(`${path}$`))
  await expect(start(page)).toBeEnabled()
})
test('접수 전 실제 대상 수를 표시하고 명시적 시작 전에는 접수하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  await open(page)
  await expect(page.getByText('복사 대상 2곡', { exact: true })).toBeVisible()
  await expect(page.getByText('건너뛸 예정 1곡', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('link', { name: 'YouTube 목록 열기' }),
  ).toHaveCount(0)
  expect(s.created).toHaveLength(0)
})
for (const [status, title, link] of [
  ['DISCONNECTED', 'YouTube 계정 연결이 필요해요', true],
  ['RECONNECT_REQUIRED', 'YouTube에 다시 연결해 주세요', true],
  ['REVOKING', '연결 해제가 끝날 때까지 기다려 주세요', false],
  ['DISABLED', '지금은 내보내기를 사용할 수 없어요', false],
] as const)
  test(`${status}에서 접수를 막는다`, async ({ page }) => {
    const s = await setup(page)
    s.connection = status
    await open(page)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    if (link)
      await expect(
        page.getByRole('link', { name: 'YouTube 연결 관리' }),
      ).toBeVisible()
    else await expect(start(page)).toBeDisabled()
    expect(s.created).toHaveLength(0)
  })
test('이용 가능한 곡이 없으면 시작하지 않는다', async ({ page }) => {
  const s = await setup(page)
  s.source.availableCount = 0
  await open(page)
  await expect(start(page)).toBeDisabled()
  await expect(
    page.getByRole('heading', { name: '내보낼 수 있는 곡이 없어요' }),
  ).toBeVisible()
  expect(s.created).toHaveLength(0)
})
test('접수 중 중복을 막고 202 이후에도 완료로 표시하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  let release!: () => void
  s.writeGate = new Promise<void>((resolve) => (release = resolve))
  await open(page)
  await start(page).click()
  await expect(page.getByRole('button', { name: '접수 중…' })).toBeDisabled()
  await expect(panel(page).getByText('0곡', { exact: true })).toHaveCount(0)
  await expect.poll(() => s.created.length).toBe(1)
  expect(s.created[0]).toMatchObject({
    requestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    version: 0,
  })
  release()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  await expect(page.getByText('대기 중', { exact: true })).toBeVisible()
  await expect(page.getByText('YouTube 내보내기가 완료됐어요')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '취소', exact: true }),
  ).toBeEnabled()
  expect(s.created).toHaveLength(1)
})
test('조회만으로 진행과 실제 부분 완료를 갱신하며 새로고침해도 재접수하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  await open(page, jobPath)
  s.job = {
    ...s.job,
    status: 'RUNNING',
    copiedCount: 1,
    remainingCount: 2,
    youtubePlaylistId: 'PL-test',
    youtubeUrl: 'https://www.youtube.com/playlist?list=PL-test',
  }
  await expect(
    page.getByRole('heading', { name: 'YouTube에 곡을 복사하고 있어요' }),
  ).toBeVisible({ timeout: 8000 })
  s.job = {
    ...s.job,
    status: 'SUCCEEDED',
    copiedCount: 2,
    skippedCount: 1,
    remainingCount: 0,
  }
  await expect(
    page.getByRole('heading', { name: '이용 불가 곡을 건너뛰고 완료했어요' }),
  ).toBeVisible({ timeout: 8000 })
  await expect(
    page.getByText(
      '복사 완료 2곡 · 건너뜀 1곡. 건너뛴 곡은 YouTube 목록에 포함되지 않습니다.',
    ),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: 'YouTube 목록 열기' }),
  ).toHaveAttribute('href', s.job.youtubeUrl!)
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '이용 불가 곡을 건너뛰고 완료했어요' }),
  ).toBeVisible()
  expect(s.created).toHaveLength(0)
  expect(s.changed).toHaveLength(0)
})
test('조회 실패는 작업 실패와 구분하고 마지막 수치로 다시 조회한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.job = { ...s.job, status: 'RUNNING', copiedCount: 1, remainingCount: 2 }
  await open(page, jobPath)
  await expect(page.getByText('진행 중', { exact: true })).toBeVisible()
  s.readFailure = 503
  await expect(page.getByText('상태 조회 오류', { exact: true })).toBeVisible({
    timeout: 10000,
  })
  await expect(
    page.getByText(/아래 수치는 마지막으로 확인한 정보/),
  ).toBeVisible()
  await expect(panel(page).locator('dd').nth(1)).toHaveText('1곡')
  await expect(retry(page)).toHaveCount(0)
  s.readFailure = 0
  s.job.status = 'CANCELLED'
  await page.getByRole('button', { name: '상태 다시 조회' }).click()
  await expect(page.getByText('취소 완료', { exact: true })).toBeVisible()
  expect(s.created).toHaveLength(0)
  expect(s.changed).toHaveLength(0)
})
for (const failure of [403, 404])
  test(`${failure}로 작업 접근이 사라지면 기존 개인 정보를 숨긴다`, async ({
    page,
  }) => {
    const s = await setup(page)
    await open(page, jobPath)
    await expect(page.getByText('대기 중', { exact: true })).toBeVisible()
    s.readFailure = failure
    await expect(
      page.getByRole('heading', { name: '내보내기 정보를 확인할 수 없습니다' }),
    ).toBeVisible({ timeout: 8000 })
    await expect(panel(page)).toHaveCount(0)
  })
test('접수 응답 유실과 새로고침 후에도 같은 요청 ID로만 확인한다', async ({
  page,
}) => {
  const s = await setup(page)
  // A completed job is excluded from /active; the original request still recovers it.
  s.job = { ...s.job, status: 'SUCCEEDED', copiedCount: 3, remainingCount: 0 }
  s.loseResponse = true
  await open(page)
  await start(page).click()
  await expect(page.getByText('상태 조회 오류', { exact: true })).toBeVisible()
  expect(s.created).toHaveLength(1)
  const original = s.created[0]
  await page.reload()
  await expect(page.getByText('상태 조회 오류', { exact: true })).toBeVisible()
  expect(s.created).toHaveLength(1)
  s.loseResponse = false
  await page.getByRole('button', { name: '상태 다시 조회' }).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created).toEqual([original, original])
})
test('접수 전 원본 변경은 최신 목록 확인 뒤 새 버전으로 명시적으로 접수한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.createFailure = { status: 409, code: 'PLAYLIST_CHANGED' }
  await open(page)
  await start(page).click()
  await expect(
    page.getByRole('heading', { name: '원본 플레이리스트가 변경됐어요' }),
  ).toBeVisible()
  expect(s.created).toHaveLength(1)
  s.source.version = 7
  s.source.name = '바뀐 목록'
  s.createFailure = null
  await page.getByRole('button', { name: '최신 목록 확인' }).click()
  await expect(page.getByRole('heading', { name: '바뀐 목록' })).toBeVisible()
  await expect(start(page)).toBeEnabled()
  expect(s.created).toHaveLength(1)
  await start(page).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  expect(s.created[1]?.version).toBe(7)
  expect(s.created[1]?.requestId).not.toBe(s.created[0]?.requestId)
})
for (const status of ['FAILED', 'UNCERTAIN'] as const)
  test(`${status}는 기존 작업만 재시도하고 취소한다`, async ({ page }) => {
    const s = await setup(page)
    s.job = { ...s.job, status, copiedCount: 1, remainingCount: 2 }
    await open(page, jobPath)
    await retry(page).click()
    await expect(page.getByText('대기 중', { exact: true })).toBeVisible()
    expect(s.changed).toEqual(['retry'])
    expect(s.created).toHaveLength(0)
    await page.getByRole('button', { name: '취소', exact: true }).click()
    await expect(page.getByText('취소 완료', { exact: true })).toBeVisible()
    expect(s.changed).toEqual(['retry', 'cancel'])
    await expect(retry(page)).toHaveCount(0)
  })
for (const [code, title] of [
  ['YOUTUBE_QUOTA_EXCEEDED', 'YouTube 할당량을 초과했어요'],
  ['YOUTUBE_CHANNEL_REQUIRED', 'YouTube 채널이 필요해요'],
  ['YOUTUBE_RECONNECT_REQUIRED', 'YouTube 접근 권한을 다시 확인해 주세요'],
  ['REMOTE_PLAYLIST_CHANGED', 'YouTube에 곡을 복사하지 못했어요'],
] as const)
  test(`${code}를 올바르게 안내한다`, async ({ page }) => {
    const s = await setup(page)
    s.job.status = 'FAILED'
    s.job.errorCode = code
    if (code === 'YOUTUBE_RECONNECT_REQUIRED')
      s.connection = 'RECONNECT_REQUIRED'
    await open(page, jobPath)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    if (s.connection === 'RECONNECT_REQUIRED')
      await expect(retry(page)).toBeDisabled()
    else await expect(retry(page)).toBeEnabled()
    expect(s.created).toHaveLength(0)
  })
test('미확정은 미복사로 단정하지 않고 실제 결과 URL만 제공한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.job = {
    ...s.job,
    status: 'UNCERTAIN',
    youtubePlaylistId: 'PL-test',
    youtubeUrl: null,
  }
  await open(page, jobPath)
  await expect(
    page.getByText('결과 미확정', { exact: true }).first(),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: '결과 확인' })).toHaveCount(0)
  s.job.youtubeUrl = 'https://www.youtube.com/playlist?list=PL-test'
  await page.reload()
  await expect(page.getByRole('link', { name: '결과 확인' })).toHaveAttribute(
    'href',
    s.job.youtubeUrl,
  )
})
test('취소와 완료가 경합하면 실제 응답의 완료 상태를 표시한다', async ({
  page,
}) => {
  const s = await setup(page)
  await open(page, jobPath)
  let release!: () => void
  s.writeGate = new Promise<void>((resolve) => (release = resolve))
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page.getByRole('button', { name: '취소 중…' })).toBeDisabled()
  s.job = { ...s.job, status: 'SUCCEEDED', copiedCount: 3, remainingCount: 0 }
  release()
  await expect(page.getByText('완료', { exact: true })).toBeVisible()
  await expect(page.getByText('취소 완료', { exact: true })).toHaveCount(0)
})
test('연결 후 원래 작업으로 복귀하며 자동 재시도하지 않는다', async ({
  page,
}) => {
  const s = await setup(page)
  s.connection = 'RECONNECT_REQUIRED'
  s.job.status = 'FAILED'
  s.job.errorCode = 'YOUTUBE_RECONNECT_REQUIRED'
  await open(page, jobPath)
  await page.getByRole('link', { name: 'YouTube 연결 관리' }).click()
  const managementPath = `/library/youtube?${new URLSearchParams({ returnTo: jobPath })}`
  await expect(page).toHaveURL(new RegExp('library/youtube\\?returnTo='))
  await page.getByRole('button', { name: '다시 연결', exact: true }).click()
  await expect.poll(() => s.authorizations.length).toBe(1)
  expect(s.authorizations[0]?.returnTo).toBe(managementPath)
  s.connection = 'CONNECTED'
  await page.goto(`${managementPath}&youtubeResult=success`)
  await expect(
    page.getByRole('link', { name: '내보내기로 돌아가기' }),
  ).toHaveAttribute('href', jobPath)
  await page.getByRole('link', { name: '내보내기로 돌아가기' }).click()
  await expect(retry(page)).toBeEnabled()
  expect(s.created).toHaveLength(0)
  expect(s.changed).toHaveLength(0)
})

test('내보내기 재로그인 복귀는 연결 관리의 원래 작업 경로를 유지한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.connection = 'RECONNECT_REQUIRED'
  s.job.status = 'FAILED'
  s.job.errorCode = 'YOUTUBE_RECONNECT_REQUIRED'
  await open(page, jobPath)
  await page.getByRole('link', { name: 'YouTube 연결 관리' }).click()
  const managementPath = `/library/youtube?${new URLSearchParams({ returnTo: jobPath })}`
  s.authorizationFailure = { status: 401, code: 'SESSION_EXPIRED' }
  await page.getByRole('button', { name: '다시 연결', exact: true }).click()
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  expect(new URL(page.url()).searchParams.get('returnTo')).toBe(managementPath)
  const attempts = s.authorizations.length
  s.loggedIn = true
  s.authorizationFailure = null
  await page.goto(`${page.url()}&loginResult=success`)
  await expect(
    page.getByRole('heading', { name: 'YouTube 연결 관리', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '내보내기로 돌아가기' }),
  ).toHaveAttribute('href', jobPath)
  expect(s.authorizations).toHaveLength(attempts)
  await page.getByRole('link', { name: '내보내기로 돌아가기' }).click()
  await expect(page).toHaveURL(new RegExp(`jobId=${jobId}$`))
  await expect(retry(page)).toBeDisabled()
  expect(s.created).toHaveLength(0)
  expect(s.changed).toHaveLength(0)
})

test('늦게 도착한 접수 응답이 떠난 화면을 다시 열지 않는다', async ({
  page,
  isMobile,
}) => {
  const s = await setup(page)
  let release!: () => void
  s.writeGate = new Promise<void>((resolve) => (release = resolve))
  await open(page)
  await start(page).click()
  await expect.poll(() => s.created.length).toBe(1)
  if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('link', { name: '검색', exact: true }).first().click()
  release()
  await expect(page).toHaveURL(/\/search$/)
  await expect(page.getByRole('heading', { name: '노래 검색' })).toBeVisible()
  expect(s.created).toHaveLength(1)
})
for (const code of ['PLAYLIST_CHANGED', 'YOUTUBE_CONNECTION_REQUIRED'])
  test(`다른 화면으로 이동 중 도착한 ${code} 오류가 이동을 되돌리지 않는다`, async ({
    page,
    isMobile,
  }) => {
    const s = await setup(page)
    let release!: () => void
    s.writeGate = new Promise<void>((resolve) => (release = resolve))
    s.createFailure = { status: 409, code }
    await open(page)
    await start(page).click()
    await expect.poll(() => s.created.length).toBe(1)
    if (isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
    await page.getByRole('link', { name: '검색', exact: true }).first().click()
    release()
    await expect(page).toHaveURL(/\/search$/)
    await expect(page.getByRole('heading', { name: '노래 검색' })).toBeVisible()
    expect(s.created).toHaveLength(1)
  })
for (const query of [
  'jobId=bad',
  `jobId=${jobId}&version=0`,
  'requestId=bad&version=0',
  `jobId=${jobId}&jobId=${jobId}`,
])
  test(`잘못된 작업 경로에 요청하지 않는다: ${query}`, async ({ page }) => {
    const s = await setup(page)
    await page.goto(`${path}?${query}`)
    await expect(
      page.getByRole('heading', { name: '내보내기 정보를 확인할 수 없습니다' }),
    ).toBeVisible()
    expect(s.reads).toBe(0)
    expect(s.created).toHaveLength(0)
  })

test('상태 조회 중 세션이 만료되면 개인 정보를 지우고 로그인으로 이동한다', async ({
  page,
}) => {
  const s = await setup(page)
  s.readFailure = 401
  await page.goto(jobPath)
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect(panel(page)).toHaveCount(0)
  expect(s.created).toHaveLength(0)
  expect(s.changed).toHaveLength(0)
})

for (const theme of ['light', 'dark'] as const)
  test(`${theme} PC·모바일 레이아웃과 주요 상태를 확인한다`, async ({
    page,
    isMobile,
  }) => {
    const s = await setup(page)
    await page.setViewportSize(
      isMobile ? { width: 390, height: 844 } : { width: 1440, height: 1024 },
    )
    await page.addInitScript(
      (t) => localStorage.setItem('stelody-theme', t),
      theme,
    )
    const visualDir = process.env.EXPORT_VISUAL_DIR
    for (const state of ['prepare', 'running', 'uncertain'] as const) {
      s.job.status = state === 'uncertain' ? 'UNCERTAIN' : 'RUNNING'
      if (state !== 'prepare') {
        s.job.copiedCount = 1
        s.job.remainingCount = 2
        s.job.youtubePlaylistId = 'PL-test'
        s.job.youtubeUrl = 'https://www.youtube.com/playlist?list=PL-test'
      }
      await page.goto(state === 'prepare' ? path : jobPath)
      await expect(panel(page).getByRole('heading', { level: 3 })).toHaveText(
        state === 'prepare'
          ? 'YouTube로 내보낼 준비가 되었어요'
          : state === 'running'
            ? 'YouTube에 곡을 복사하고 있어요'
            : '일부 곡의 반영 결과를 확인하지 못했어요',
      )
      await page.evaluate(() => document.fonts.ready)
      const box = await panel(page).boundingBox()
      expect(box?.width).toBe(isMobile ? 350 : 760)
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBe(isMobile ? 390 : 1440)
      if (visualDir)
        await page.screenshot({
          path: `${visualDir}/export-${isMobile ? 'mobile' : 'desktop'}-${theme}-${state}.png`,
          fullPage: true,
        })
    }
  })
