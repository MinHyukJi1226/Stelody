import type { Page } from '@playwright/test'
import { test, expect, makeSong, emptyPage } from './fixtures'
import { detail, song, makeViews } from './song-detail-fixtures'
import type { Playlist, PlaylistItem } from '../src/features/playlists/api'

const user = {
  id: makeSong(800).id,
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const first = makeSong(900).id
const second = makeSong(901).id
function list(id = first, name = '밤에 듣는 목소리'): Playlist {
  return {
    id,
    name,
    createdAt: '2026-10-20T01:00:00Z',
    updatedAt: '2026-10-20T01:00:00Z',
    version: 0,
    totalCount: 0,
    availableCount: 0,
    containsSong: null,
  }
}
function entry(position = 0, available = true): PlaylistItem {
  return {
    id: makeSong(950 + position).id,
    songId: available ? song.id : makeSong(12).id,
    song: available ? song : null,
    available,
    unavailableMessage: available ? null : '저장한 순서는 유지됩니다.',
    position,
    addedAt: '2026-10-20T01:00:00Z',
  }
}
async function setup(page: Page, initial: Playlist[] = [list()]) {
  const state = {
    lists: initial,
    items: new Map<string, PlaylistItem[]>(),
    creates: 0,
    adds: [] as { id: string; version: number; songId: string }[],
    addError: '',
    listError: 0,
    loggedIn: true,
    loseCreate: false,
    delayCreate: Promise.resolve(),
  }
  await page.route('**/api/v1/me', (r) =>
    r.fulfill(
      state.loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test' } }),
  )
  await page.route('**/api/v1/me/playlists**', async (r) => {
    const request = r.request(),
      url = new URL(request.url()),
      parts = url.pathname.split('/'),
      id = parts[5]
    if (!id) {
      if (request.method() === 'GET') {
        if (state.listError)
          return r.fulfill({
            status: state.listError,
            json: {
              code: state.listError === 401 ? 'SESSION_EXPIRED' : 'UNAVAILABLE',
            },
          })
        const songId = url.searchParams.get('songId')
        return r.fulfill({
          json: {
            ...emptyPage,
            totalCount: state.lists.length,
            items: state.lists.map((p) => ({
              ...p,
              containsSong: songId
                ? !!state.items.get(p.id)?.some((i) => i.songId === songId)
                : null,
            })),
          },
        })
      }
      const body = await request.postDataJSON()
      state.creates++
      await state.delayCreate
      const value = list(makeSong(1000 + state.creates).id, body.name)
      state.lists.unshift(value)
      if (state.loseCreate) return r.abort('failed')
      return r.fulfill({ status: 201, json: value })
    }
    const value = state.lists.find((p) => p.id === id)
    if (!value)
      return r.fulfill({ status: 404, json: { code: 'PLAYLIST_NOT_FOUND' } })
    if (!parts[6]) return r.fulfill({ json: value })
    if (request.method() === 'GET')
      return r.fulfill({
        json: {
          ...emptyPage,
          totalCount: value.totalCount,
          availableCount: value.availableCount,
          version: value.version,
          items: state.items.get(id) ?? [],
        },
      })
    const body = await request.postDataJSON()
    state.adds.push({ id, ...body })
    if (state.addError) {
      if (state.addError === 'PLAYLIST_CHANGED') value.version++
      return r.fulfill({
        status: state.addError === 'ACCESS_DENIED' ? 403 : 409,
        json: { code: state.addError },
      })
    }
    if (body.version !== value.version)
      return r.fulfill({ status: 409, json: { code: 'PLAYLIST_CHANGED' } })
    if (state.items.get(id)?.some((i) => i.songId === body.songId))
      return r.fulfill({
        status: 409,
        json: { code: 'PLAYLIST_SONG_ALREADY_EXISTS' },
      })
    const added = { ...entry(value.totalCount), songId: body.songId }
    state.items.set(id, [...(state.items.get(id) ?? []), added])
    value.totalCount++
    value.availableCount++
    value.version++
    return r.fulfill({
      status: 201,
      json: { playlist: value, itemId: added.id },
    })
  })
  await page.route(`**/api/v1/songs/${song.id}`, (r) =>
    r.fulfill({ json: detail }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (r) =>
    r.fulfill({ json: makeViews() }),
  )
  return state
}
const picker = (page: Page) =>
  page.getByRole('dialog', { name: '플레이리스트에 추가', exact: true })
async function open(page: Page) {
  await page.goto(`/songs/${song.id}`)
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await expect(picker(page)).toBeVisible()
}

test('빈 목록에서 이름을 검증하고 생성 후 빈 상세로 이동하며 새로고침에도 유지한다', async ({
  page,
}) => {
  const state = await setup(page, [])
  await page.goto('/library/playlists')
  await expect(
    page.getByRole('heading', { name: '아직 만든 플레이리스트가 없습니다' }),
  ).toBeVisible()
  await expect(
    page
      .getByRole('navigation', { name: '보관함 종류' })
      .getByRole('link', { name: '즐겨찾기', exact: true }),
  ).toBeVisible()
  await page.getByRole('link', { name: '첫 목록 만들기' }).click()
  const input = page.getByRole('textbox', { name: '플레이리스트 이름' })
  await page.getByRole('button', { name: '만들고 곡 담기' }).click()
  await expect(
    page.getByText('이름을 입력해 주세요.', { exact: true }),
  ).toBeVisible()
  await input.fill('가'.repeat(51))
  await page.getByRole('button', { name: '만들고 곡 담기' }).click()
  await expect(
    page.getByText('이름은 50자까지 입력할 수 있어요.'),
  ).toBeVisible()
  expect(state.creates).toBe(0)
  await input.fill('  새 목록  ')
  await page.getByRole('button', { name: '만들고 곡 담기' }).click()
  await expect(page).toHaveURL(`/library/playlists/${makeSong(1001).id}`)
  await expect(
    page.getByRole('heading', { name: '아직 담은 곡이 없습니다' }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '아직 담은 곡이 없습니다' }),
  ).toBeVisible()
  expect(state.lists[0]?.name).toBe('새 목록')
  expect(state.creates).toBe(1)
})

test('저장·이용 가능 곡 수와 서버 순서를 표시하고 이용 불가 곡은 링크를 숨긴다', async ({
  page,
}) => {
  const state = await setup(page, [
    { ...list(), totalCount: 2, availableCount: 1 },
    list(second, '출근길의 작은 별'),
  ])
  state.items.set(first, [entry(), entry(1, false)])
  await page.goto('/library/playlists')
  await expect(page.getByText('2곡 · 이용 가능 1곡 · 나만 보기')).toBeVisible()
  await page
    .getByRole('article')
    .filter({
      has: page.getByRole('heading', { name: '밤에 듣는 목소리', exact: true }),
    })
    .getByRole('link', { name: '목록 열기' })
    .click()
  await expect(
    page.getByRole('heading', { name: '밤에 듣는 목소리', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: song.title, exact: true }),
  ).toBeVisible()
  await expect(page.getByText('총 2곡', { exact: true })).toBeVisible()
  await expect(
    page.getByText('이용 불가 곡 1곡', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('원곡 · 원곡 가수', { exact: true }),
  ).toBeVisible()
  const unavailable = page
    .locator('article')
    .filter({ hasText: '현재 이용할 수 없는 곡' })
  await expect(unavailable).toBeVisible()
  await expect(unavailable.getByRole('link')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '이름 수정', exact: true }),
  ).toBeEnabled()
  await expect(
    page.getByRole('button', { name: '목록 삭제', exact: true }),
  ).toBeEnabled()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('추가 선택창은 이미 담긴 목록을 잠그고 선택한 목록 버전으로 한 번 추가한다', async ({
  page,
}) => {
  const state = await setup(page, [
    { ...list(), totalCount: 1, availableCount: 1 },
    list(second, '새벽 노래'),
  ])
  state.items.set(first, [entry()])
  await open(page)
  await expect(
    picker(page).getByRole('button', { name: '밤에 듣는 목소리 추가됨' }),
  ).toBeDisabled()
  await picker(page)
    .getByRole('button', { name: '새벽 노래 이 목록에 담기' })
    .click()
  await expect(picker(page)).toHaveCount(0)
  expect(state.adds).toEqual([{ id: second, version: 0, songId: song.id }])
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await expect(
    picker(page).getByRole('button', { name: '새벽 노래 추가됨' }),
  ).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(picker(page)).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '＋ 플레이리스트', exact: true }),
  ).toBeFocused()
})

test('만들고 담기는 추가 실패 후 새 목록을 다시 생성하지 않고 재시도한다', async ({
  page,
}) => {
  const state = await setup(page, [])
  state.addError = 'ACCESS_DENIED'
  await open(page)
  await picker(page).getByRole('button', { name: '＋ 새 플레이리스트' }).click()
  const dialog = page.getByRole('dialog', {
    name: '새 플레이리스트',
    exact: true,
  })
  await expect(
    dialog.getByRole('textbox', { name: '이름', exact: true }),
  ).toBeFocused()
  await dialog
    .getByRole('textbox', { name: '이름', exact: true })
    .fill('새벽 노래')
  await dialog.getByRole('button', { name: '만들고 담기' }).click()
  await expect(dialog.getByRole('alert')).toContainText(
    '목록은 만들어졌지만 곡을 담지 못했어요.',
  )
  expect(state.creates).toBe(1)
  state.addError = ''
  await dialog.getByRole('button', { name: '곡 담기 다시 시도' }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.creates).toBe(1)
  expect(state.adds).toHaveLength(2)
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await expect(
    picker(page).getByRole('button', { name: '새벽 노래 추가됨' }),
  ).toBeDisabled()
})

test('버전 충돌은 최신 목록을 보여주고 사용자 재시도에서 새 버전을 사용한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.addError = 'PLAYLIST_CHANGED'
  await open(page)
  await picker(page)
    .getByRole('button', { name: '밤에 듣는 목소리 이 목록에 담기' })
    .click()
  await expect(picker(page).getByRole('alert')).toContainText(
    '목록이 변경되었어요.',
  )
  expect(state.adds).toHaveLength(1)
  state.addError = ''
  await picker(page)
    .getByRole('button', { name: '밤에 듣는 목소리 이 목록에 담기' })
    .click()
  await expect(picker(page)).toHaveCount(0)
  expect(state.adds.map((x) => x.version)).toEqual([0, 1])
})

test('생성 요청 결과가 불확실하면 생성 재전송을 막고 목록 확인으로 안내한다', async ({
  page,
}) => {
  const state = await setup(page, [])
  state.loseCreate = true
  await page.goto('/library/playlists/new')
  await page
    .getByRole('textbox', { name: '플레이리스트 이름' })
    .fill('응답 유실 목록')
  await page.getByRole('button', { name: '만들고 곡 담기' }).click()
  await expect(page.getByRole('alert')).toContainText(
    '처리 결과를 확인하지 못했어요.',
  )
  await expect(
    page.getByRole('button', { name: '만들고 곡 담기' }),
  ).toBeDisabled()
  expect(state.creates).toBe(1)
  await page.getByRole('link', { name: '목록 확인' }).click()
  await expect(
    page.getByRole('heading', { name: '응답 유실 목록' }),
  ).toBeVisible()
})

test('비로그인 추가는 Google 복귀 후 선택창만 열고 자동으로 생성·추가하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  state.loggedIn = false
  await page.route('**/api/v1/auth/google?*', async (r) => {
    state.loggedIn = true
    const target = new URL(
      new URL(r.request().url()).searchParams.get('returnTo')!,
      page.url(),
    )
    target.searchParams.set('loginResult', 'success')
    await r.fulfill({
      status: 302,
      headers: { Location: target.pathname + target.search },
    })
  })
  await page.goto(`/songs/${song.id}`)
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await expect(page).toHaveURL(`/login?returnTo=%2Fsongs%2F${song.id}`)
  await page.getByRole('link', { name: 'Google로 로그인', exact: true }).click()
  await expect(picker(page)).toBeVisible()
  expect(state.creates).toBe(0)
  expect(state.adds).toHaveLength(0)
  await picker(page).getByRole('button', { name: '닫기' }).click()
  await expect(picker(page)).toHaveCount(0)
  await page.reload()
  await expect(picker(page)).toHaveCount(0)
})

test('Google 복귀로 열린 선택창에서 만들고 담기 성공 후 재시도 버튼 없이 닫힌다', async ({
  page,
}) => {
  const state = await setup(page, [])
  state.loggedIn = false
  await page.route('**/api/v1/auth/google?*', async (r) => {
    state.loggedIn = true
    const target = new URL(
      new URL(r.request().url()).searchParams.get('returnTo')!,
      page.url(),
    )
    target.searchParams.set('loginResult', 'success')
    await r.fulfill({
      status: 302,
      headers: { Location: target.pathname + target.search },
    })
  })
  await page.goto(`/songs/${song.id}`)
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await page.getByRole('link', { name: 'Google로 로그인', exact: true }).click()
  await expect(picker(page)).toBeVisible()
  await picker(page).getByRole('button', { name: '＋ 새 플레이리스트' }).click()
  const dialog = page.getByRole('dialog', {
    name: '새 플레이리스트',
    exact: true,
  })
  await dialog
    .getByRole('textbox', { name: '이름', exact: true })
    .fill('로그인 후 목록')
  await dialog.getByRole('button', { name: '만들고 담기', exact: true }).click()
  await expect(
    page.getByText('플레이리스트에 추가했어요.', { exact: true }),
  ).toBeVisible()
  await expect(dialog).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '곡 담기 다시 시도' }),
  ).toHaveCount(0)
  expect(state.creates).toBe(1)
  expect(state.adds).toHaveLength(1)
  await page
    .getByRole('button', { name: '＋ 플레이리스트', exact: true })
    .click()
  await expect(
    picker(page).getByRole('button', { name: '로그인 후 목록 추가됨' }),
  ).toBeDisabled()
  expect(state.adds).toHaveLength(1)
})

test('목록 로딩·오류·재시도와 세션 만료를 구분한다', async ({ page }) => {
  const state = await setup(page)
  state.listError = 503
  await page.goto('/library/playlists')
  await expect(
    page.getByRole('heading', { name: '불러오지 못했어요' }),
  ).toBeVisible()
  state.listError = 0
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(
    page.getByRole('heading', { name: '밤에 듣는 목소리' }),
  ).toBeVisible()
  state.listError = 401
  state.loggedIn = false
  await page.clock.install()
  await page.clock.fastForward(31_000)
  await page.evaluate(() =>
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true })),
  )
  await expect(page).toHaveURL('/login?returnTo=%2Flibrary%2Fplaylists')
  await expect(
    page.getByRole('heading', { name: '밤에 듣는 목소리' }),
  ).toHaveCount(0)
})

test('상세의 잘못된 UUID는 개인 곡 API를 호출하지 않고 접근 불가를 안내한다', async ({
  page,
}) => {
  await setup(page)
  let reads = 0
  await page.route('**/api/v1/me/playlists/not-an-id**', async (r) => {
    reads++
    await r.fallback()
  })
  await page.goto('/library/playlists/not-an-id')
  await expect(
    page.getByRole('heading', { name: '이 목록에 접근할 수 없습니다' }),
  ).toBeVisible()
  expect(reads).toBe(0)
})

test('전체 페이지 버전 충돌은 편집을 막고 첫 페이지부터 다시 조회한다', async ({
  page,
}) => {
  const state = await setup(page, [
    { ...list(), totalCount: 2, availableCount: 2 },
  ])
  let reset = false
  const cursors: (string | null)[] = []
  await page.route(`**/api/v1/me/playlists/${first}/items?*`, async (r) => {
    const cursor = new URL(r.request().url()).searchParams.get('cursor')
    cursors.push(cursor)
    if (cursor)
      return r.fulfill({ status: 409, json: { code: 'PLAYLIST_CHANGED' } })
    return r.fulfill({
      json: {
        ...emptyPage,
        version: reset ? 1 : 0,
        totalCount: reset ? 1 : 2,
        availableCount: reset ? 1 : 2,
        items: [entry()],
        hasNext: !reset,
        nextCursor: reset ? null : 'next',
      },
    })
  })
  await page.goto(`/library/playlists/${first}`)
  await expect(page.getByText(/^이용 불가 곡 \d+곡$/)).toHaveCount(0)
  await expect(
    page.getByText('전체 목록을 다시 불러와 최신 내용을 확인해 주세요.'),
  ).toBeVisible()
  await expect(page.getByRole('heading', { name: song.title })).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toHaveCount(0)
  reset = true
  Object.assign(state.lists[0]!, {
    totalCount: 1,
    availableCount: 1,
    version: 1,
  })
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByRole('heading', { name: song.title })).toBeVisible()
  expect(cursors).toEqual([null, 'next', null])
})

for (const code of [
  'PLAYLIST_ITEM_LIMIT_REACHED',
  'PLAYLIST_SONG_ALREADY_EXISTS',
]) {
  test(`곡 추가 ${code} 오류는 성공으로 처리하거나 자동 재전송하지 않는다`, async ({
    page,
  }) => {
    const state = await setup(page)
    state.addError = code
    await open(page)
    await picker(page)
      .getByRole('button', { name: '밤에 듣는 목소리 이 목록에 담기' })
      .click()
    await expect(picker(page).getByRole('alert')).toContainText(
      code === 'PLAYLIST_ITEM_LIMIT_REACHED'
        ? '곡 수를 초과'
        : '이미 이 목록에 담긴 곡',
    )
    expect(state.adds).toHaveLength(1)
    expect(state.lists[0]?.totalCount).toBe(0)
  })
}

test('생성 중 버튼·취소·Escape를 막아 중복 생성을 방지한다', async ({
  page,
}) => {
  const state = await setup(page, [])
  let release: () => void = () => {}
  state.delayCreate = new Promise<void>((resolve) => {
    release = resolve
  })
  await open(page)
  await picker(page).getByRole('button', { name: '＋ 새 플레이리스트' }).click()
  const dialog = page.getByRole('dialog', {
    name: '새 플레이리스트',
    exact: true,
  })
  await dialog
    .getByRole('textbox', { name: '이름', exact: true })
    .fill('중복 없는 목록')
  await dialog.getByRole('button', { name: '만들고 담기' }).click()
  await expect(dialog.getByRole('button', { name: '저장 중…' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '취소' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  release()
  await expect(dialog).toHaveCount(0)
  expect(state.creates).toBe(1)
  expect(state.adds).toHaveLength(1)
})
