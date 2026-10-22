import type { Page } from '@playwright/test'
import { test, expect, makeSong, emptyPage } from './fixtures'
import type { Playlist, PlaylistItem } from '../src/features/playlists/api'

const user = {
  id: makeSong(800).id,
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const id = makeSong(900).id
const path = `/library/playlists/${id}`
const one = makeSong(1, { title: '첫 번째 노래' })
const two = makeSong(2, { title: '두 번째 노래', type: 'ORIGINAL' })
const initial: PlaylistItem[] = [one, two, null].map((song, position) => ({
  id: makeSong(950 + position).id,
  songId: song?.id ?? makeSong(3).id,
  song,
  available: !!song,
  unavailableMessage: song ? null : '저장한 순서는 유지됩니다.',
  position,
  addedAt: '2026-10-20T01:00:00Z',
}))
type Write = {
  method: string
  kind: string
  version: number
  body: Record<string, unknown> | null
}

async function setup(page: Page, entries = initial) {
  const playlist: Playlist = {
    id,
    name: '밤에 듣는 목소리',
    totalCount: entries.length,
    availableCount: entries.filter((i) => i.available).length,
    version: 0,
    containsSong: null,
    createdAt: '2026-10-20T01:00:00Z',
    updatedAt: '2026-10-20T01:00:00Z',
  }
  const state = {
    playlist,
    items: structuredClone(entries),
    writes: [] as Write[],
    reads: [] as (string | null)[],
    deleted: false,
    loggedIn: true,
    failure: null as null | {
      status: number
      code: string
      fieldErrors?: unknown[]
    },
    loseResponse: false,
    pageFailure: false,
    readFailure: 0,
    gate: Promise.resolve(),
    pageGate: Promise.resolve(),
  }
  await page.route('**/api/v1/me', (r) =>
    r.fulfill(
      state.loggedIn
        ? { json: user }
        : { status: 401, json: { code: 'SESSION_EXPIRED' } },
    ),
  )
  await page.route('**/api/v1/auth/csrf', (r) =>
    r.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test' } }),
  )
  await page.route('**/api/v1/me/playlists**', async (r) => {
    const request = r.request(),
      url = new URL(request.url()),
      parts = url.pathname.split('/'),
      method = request.method()
    if (!parts[5])
      return r.fulfill({
        json: {
          ...emptyPage,
          totalCount: state.deleted ? 0 : 1,
          items: state.deleted ? [] : [state.playlist],
        },
      })
    if (state.deleted)
      return r.fulfill({ status: 404, json: { code: 'PLAYLIST_NOT_FOUND' } })
    if (method === 'GET') {
      if (state.readFailure)
        return r.fulfill({
          status: state.readFailure,
          json: { code: 'UNAVAILABLE' },
        })
      if (!parts[6]) return r.fulfill({ json: state.playlist })
      const cursor = url.searchParams.get('cursor')
      state.reads.push(cursor)
      if (cursor) {
        await state.pageGate
        if (state.pageFailure)
          return r.fulfill({ status: 409, json: { code: 'PLAYLIST_CHANGED' } })
      }
      const offset = cursor ? Number(cursor.split(':')[1]) : 0
      if (cursor && Number(cursor.split(':')[0]) !== state.playlist.version)
        return r.fulfill({ status: 409, json: { code: 'PLAYLIST_CHANGED' } })
      const items = state.items.slice(offset, offset + 2),
        hasNext = offset + 2 < state.items.length
      return r.fulfill({
        json: {
          ...state.playlist,
          items,
          hasNext,
          nextCursor: hasNext
            ? `${state.playlist.version}:${offset + 2}`
            : null,
        },
      })
    }
    const body =
      method === 'PATCH' || method === 'PUT'
        ? await request.postDataJSON()
        : null
    const version = body?.version ?? Number(url.searchParams.get('version'))
    const kind =
      method === 'PATCH'
        ? 'rename'
        : parts[6] === 'order'
          ? 'order'
          : parts[7]
            ? 'remove'
            : 'delete'
    state.writes.push({ method, kind, version, body })
    expect(request.headers()['x-csrf-token']).toBe('test')
    await state.gate
    if (state.failure)
      return r.fulfill({ status: state.failure.status, json: state.failure })
    if (version !== state.playlist.version)
      return r.fulfill({ status: 409, json: { code: 'PLAYLIST_CHANGED' } })
    if (kind === 'rename') state.playlist.name = body.name
    if (kind === 'delete') state.deleted = true
    if (kind === 'remove')
      state.items = state.items.filter((i) => i.id !== parts[7])
    if (kind === 'order') {
      expect([...body.itemIds].sort()).toEqual(
        state.items.map((i) => i.id).sort(),
      )
      const byId = new Map(state.items.map((i) => [i.id, i]))
      state.items = body.itemIds.map((key: string) => byId.get(key)!)
    }
    state.items = state.items.map((item, position) => ({ ...item, position }))
    state.playlist.version++
    state.playlist.totalCount = state.items.length
    state.playlist.availableCount = state.items.filter(
      (i) => i.available,
    ).length
    if (state.loseResponse) return r.abort('failed')
    return kind === 'delete'
      ? r.fulfill({ status: 204 })
      : r.fulfill({ json: state.playlist })
  })
  return state
}
const order = (page: Page) =>
  page.getByRole('list', { name: '플레이리스트 곡 순서' })
const rows = (page: Page) => order(page).getByRole('listitem')
async function visit(page: Page) {
  await page.goto(path)
  await expect(
    page.getByRole('heading', { name: '밤에 듣는 목소리', exact: true }),
  ).toBeVisible()
}
async function down(page: Page) {
  await page.getByRole('button', { name: '첫 번째 노래 아래로 이동' }).click()
}

test('전체 페이지와 이용 불가 곡을 유지하며 위아래 편집·취소·저장·새로고침을 처리한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  expect(state.reads).toEqual([null, '0:2'])
  await expect(rows(page)).toHaveCount(3)
  await expect(rows(page).nth(2).getByRole('link')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '첫 번째 노래 위로 이동' }),
  ).toBeDisabled()
  await down(page)
  await expect(rows(page).first()).toContainText(two.title)
  await expect(
    page.getByRole('button', { name: '이름 수정', exact: true }),
  ).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '목록 삭제', exact: true }),
  ).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '첫 번째 노래 목록에서 제거' }),
  ).toBeDisabled()
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(rows(page).first()).toContainText(one.title)
  expect(state.writes).toHaveLength(0)
  await down(page)
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(
    page.getByText('플레이리스트 순서를 저장했어요.', { exact: true }),
  ).toBeVisible()
  expect(state.writes).toEqual([
    {
      method: 'PUT',
      kind: 'order',
      version: 0,
      body: {
        version: 0,
        itemIds: [initial[1]!.id, initial[0]!.id, initial[2]!.id],
      },
    },
  ])
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeDisabled()
  await page.reload()
  await expect(rows(page).first()).toContainText(two.title)
  await expect(rows(page).nth(2)).toContainText('현재 이용할 수 없는 곡')
})

test('저장하지 않은 순서가 있으면 다른 화면 이동을 막고 취소 후 다시 이동한다', async ({
  page,
}) => {
  await setup(page)
  await visit(page)
  await down(page)
  const brand = page.getByRole('link', { name: 'Stelody 홈', exact: true })
  await brand.click()
  await expect(page).toHaveURL(path)
  await expect(page.getByRole('alert')).toContainText(
    '순서 변경을 저장하거나 취소한 뒤 이동해 주세요.',
  )
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await brand.click()
  await expect(page).toHaveURL('/')
})

test('키보드 드래그로 순서를 바꾸고 Escape로 진행 중 이동을 취소한다', async ({
  page,
}) => {
  await setup(page)
  await visit(page)
  const first = rows(page).filter({ hasText: one.title })
  // A real key press gives the keyboard sensor time to attach its deferred listener.
  await first.focus()
  await page.keyboard.press('Space', { delay: 50 })
  await expect(first).toHaveClass(/opacity-70/)
  await page.keyboard.press('ArrowDown')
  await expect(
    page.getByText('첫 번째 노래, 2번째 위치입니다.', { exact: true }),
  ).toBeAttached()
  await page.keyboard.press('Space', { delay: 50 })
  await expect(rows(page).first()).toContainText(two.title)
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await first.focus()
  await page.keyboard.press('Space', { delay: 50 })
  await expect(first).toHaveClass(/opacity-70/)
  await page.keyboard.press('ArrowDown')
  await expect(
    page.getByText('첫 번째 노래, 2번째 위치입니다.', { exact: true }),
  ).toBeAttached()
  await page.keyboard.press('Escape')
  await expect(rows(page).first()).toContainText(one.title)
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeDisabled()
})

test('모바일에서 길게 누른 터치 드래그로 순서를 바꾼다', async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, '모바일 터치 입력 전용')
  await setup(page)
  await visit(page)
  await rows(page).nth(1).scrollIntoViewIfNeeded()
  const first = rows(page).first(),
    from = await first.boundingBox(),
    to = await rows(page).nth(1).boundingBox()
  if (!from || !to) throw new Error('곡 카드가 없습니다')
  const session = await page.context().newCDPSession(page)
  const x = from.x + 8,
    y = from.y + from.height / 2,
    end = to.y + to.height / 2
  await session.send('Input.dispatchTouchEvent', {
    type: 'touchStart',
    touchPoints: [{ x, y, id: 1 }],
  })
  await expect(first).toHaveClass(/opacity-70/)
  for (let i = 1; i <= 12; i++)
    await session.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x, y: y + ((end - y) * i) / 12, id: 1 }],
    })
  await session.send('Input.dispatchTouchEvent', {
    type: 'touchEnd',
    touchPoints: [],
  })
  await expect(rows(page).first()).toContainText(two.title)
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeEnabled()
})

test('마우스 드래그는 상세 이동 없이 순서를 편집한다', async ({ page }) => {
  await setup(page)
  await visit(page)
  await rows(page).nth(1).scrollIntoViewIfNeeded()
  const from = await rows(page).first().boundingBox(),
    to = await rows(page).nth(1).boundingBox()
  if (!from || !to) throw new Error('곡 카드가 없습니다')
  await page.mouse.move(from.x + 8, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(from.x + 20, from.y + from.height / 2, { steps: 3 })
  await page.mouse.move(to.x + 20, to.y + to.height / 2, { steps: 12 })
  await page.mouse.up()
  await expect(rows(page).first()).toContainText(two.title)
  await expect(page).toHaveURL(path)
})

test('이름 수정은 현재 이름·검증·실패 입력 유지·저장 후 상세 복귀를 제공한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await page.getByRole('button', { name: '이름 수정', exact: true }).click()
  await expect(page).toHaveURL(`${path}/edit`)
  const input = page.getByRole('textbox', { name: '플레이리스트 이름' })
  await expect(input).toHaveValue('밤에 듣는 목소리')
  await input.fill('  ')
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(
    page.getByText('이름을 입력해 주세요.', { exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(0)
  const name = '🎵'.repeat(50)
  await input.fill(`  ${name}  `)
  state.failure = {
    status: 400,
    code: 'INVALID_PLAYLIST_REQUEST',
    fieldErrors: [
      { field: 'name', code: 'INVALID_NAME', message: '이름을 확인해 주세요.' },
    ],
  }
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(
    page.getByText('이름을 확인해 주세요.', { exact: true }),
  ).toBeVisible()
  await expect(input).toHaveValue(`  ${name}  `)
  state.failure = null
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(page).toHaveURL(path)
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  expect(state.writes.at(-1)).toEqual({
    method: 'PATCH',
    kind: 'rename',
    version: 0,
    body: { name, version: 0 },
  })
  await page.reload()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
})

test('곡 제거는 항목 ID·버전을 사용하고 다른 곡·이용 불가 자리와 합계를 유지한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await page.getByRole('button', { name: '첫 번째 노래 목록에서 제거' }).click()
  await expect(rows(page)).toHaveCount(2)
  await expect(page.getByText('총 2곡', { exact: true })).toBeVisible()
  await expect(
    page.getByText('이용 불가 곡 1곡', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '이용 불가 곡 목록에서 제거' }).click()
  await expect(rows(page)).toHaveCount(1)
  await expect(page.getByText('이용 불가 곡 1곡', { exact: true })).toHaveCount(
    0,
  )
  expect(state.writes.map((w) => [w.kind, w.version])).toEqual([
    ['remove', 0],
    ['remove', 1],
  ])
  await page.reload()
  await expect(rows(page).first()).toContainText(two.title)
})

test('삭제 확인은 이름과 영향을 표시하고 취소는 유지, 삭제 성공은 목록으로 복귀한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  const trigger = page.getByRole('button', { name: '목록 삭제', exact: true })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '이 목록을 삭제할까요?' })
  await expect(dialog).toContainText('밤에 듣는 목소리')
  await expect(dialog).toContainText('담긴 항목 3개')
  await expect(dialog).toContainText(
    '다른 플레이리스트, 즐겨찾기, 원본 곡은 유지됩니다.',
  )
  await dialog.getByRole('button', { name: '취소' }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.writes).toHaveLength(0)
  await expect(trigger).toBeFocused()
  await trigger.click()
  await dialog.getByRole('button', { name: '목록 삭제', exact: true }).click()
  await expect(page).toHaveURL('/library/playlists')
  await expect(
    page.getByRole('heading', { name: '아직 만든 플레이리스트가 없습니다' }),
  ).toBeVisible()
  expect(state.writes).toEqual([
    { method: 'DELETE', kind: 'delete', version: 0, body: null },
  ])
})

test('빈 목록도 이름 수정과 삭제를 할 수 있다', async ({ page }) => {
  await setup(page, [])
  await page.goto('/library/playlists')
  const card = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '밤에 듣는 목소리' }),
  })
  await expect(
    card.getByRole('link', { name: '곡 찾기', exact: true }),
  ).toHaveAttribute('href', '/search')
  await card.getByRole('link', { name: '목록 열기', exact: true }).click()
  await expect(page).toHaveURL(path)
  await expect(
    page.getByRole('heading', { name: '아직 담은 곡이 없습니다' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '이름 수정', exact: true }).click()
  await page
    .getByRole('textbox', { name: '플레이리스트 이름' })
    .fill('빈 목록 이름')
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(page).toHaveURL(path)
  await page.getByRole('button', { name: '목록 삭제', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('담긴 항목 0개')
  await dialog.getByRole('button', { name: '목록 삭제', exact: true }).click()
  await expect(page).toHaveURL('/library/playlists')
})

test('전체 조회 중 버전 충돌은 일부 곡 편집을 막고 전체 재조회 후 편집을 허용한다', async ({
  page,
}) => {
  const state = await setup(page)
  state.pageFailure = true
  await page.goto(path)
  await expect(
    page.getByRole('heading', { name: '목록이 변경되었습니다' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toHaveCount(0)
  expect(state.writes).toHaveLength(0)
  state.pageFailure = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(rows(page)).toHaveCount(3)
  expect(state.reads).toEqual([null, '0:2', null, '0:2'])
})

test('순서 저장 충돌은 기존 버전 재전송을 막고 전체 재조회 후 새 편집만 저장한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await down(page)
  state.playlist.version = 1
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(page.getByRole('alert')).toContainText('목록이 변경되었어요.')
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeDisabled()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(rows(page).first()).toContainText(one.title)
  await down(page)
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(
    page.getByText('플레이리스트 순서를 저장했어요.', { exact: true }),
  ).toBeVisible()
  expect(state.writes.map((w) => w.version)).toEqual([0, 1])
})

test('순서 저장 응답 유실은 성공으로 안내하거나 자동 재전송하지 않는다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await down(page)
  state.loseResponse = true
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(page.getByRole('alert')).toContainText(
    '처리 결과를 확인하지 못했어요.',
  )
  await expect(
    page.getByText('플레이리스트 순서를 저장했어요.', { exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeDisabled()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(rows(page).first()).toContainText(two.title)
  await expect(
    page.getByRole('button', { name: '변경 순서 저장' }),
  ).toBeDisabled()
  expect(state.writes).toHaveLength(1)
})

test('저장 중에는 중복 저장·다른 변경을 막는다', async ({ page }) => {
  const state = await setup(page)
  await visit(page)
  await down(page)
  let release = () => {}
  state.gate = new Promise<void>((r) => {
    release = r
  })
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(page.getByRole('button', { name: '저장 중…' })).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '취소', exact: true }),
  ).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '목록 삭제', exact: true }),
  ).toBeDisabled()
  release()
  await expect(
    page.getByText('플레이리스트 순서를 저장했어요.', { exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(1)
})

test('이름 수정 충돌은 입력을 유지하고 최신 버전을 확인한 다음에만 저장한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await page.getByRole('button', { name: '이름 수정', exact: true }).click()
  const input = page.getByRole('textbox', { name: '플레이리스트 이름' })
  await input.fill('내가 입력한 이름')
  state.playlist.version = 1
  state.playlist.name = '다른 곳에서 바꾼 이름'
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(page.getByRole('alert')).toContainText('목록이 변경되었어요.')
  await expect(input).toHaveValue('내가 입력한 이름')
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: '최신 목록 확인' }).click()
  await expect(page.getByRole('status')).toContainText(
    '입력한 이름을 확인한 뒤 다시 저장',
  )
  await expect(input).toHaveValue('내가 입력한 이름')
  await page.getByRole('button', { name: '이름 저장' }).click()
  await expect(page).toHaveURL(path)
  await expect(
    page.getByRole('heading', { name: '내가 입력한 이름', exact: true }),
  ).toBeVisible()
  expect(state.writes.map((w) => w.version)).toEqual([0, 1])
})

test('목록 삭제 충돌은 최신 내용을 다시 확인한 뒤 새 확인 모달에서 삭제한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await page.getByRole('button', { name: '목록 삭제', exact: true }).click()
  const dialog = page.getByRole('dialog')
  state.playlist.version = 1
  await dialog.getByRole('button', { name: '목록 삭제', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('목록이 변경되었어요.')
  expect(state.writes).toHaveLength(1)
  await dialog.getByRole('button', { name: '최신 목록 확인' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '목록 삭제', exact: true }),
  ).toBeEnabled()
  await page.getByRole('button', { name: '목록 삭제', exact: true }).click()
  await dialog.getByRole('button', { name: '목록 삭제', exact: true }).click()
  await expect(page).toHaveURL('/library/playlists')
  expect(state.writes.map((w) => w.version)).toEqual([0, 1])
})

test('변경 요청 401은 로그인으로 이동하고 개인 편집 내용을 숨긴다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await down(page)
  state.failure = { status: 401, code: 'SESSION_EXPIRED' }
  state.loggedIn = false
  await page.getByRole('button', { name: '변경 순서 저장' }).click()
  await expect(page).toHaveURL(`/login?returnTo=${encodeURIComponent(path)}`)
  await expect(order(page)).toHaveCount(0)
  expect(state.writes).toHaveLength(1)
  await expect(
    page.getByRole('link', { name: 'Google로 로그인', exact: true }),
  ).toBeVisible()
})

test('이름 수정 취소는 저장하지 않고 기존 이름으로 상세에 복귀한다', async ({
  page,
}) => {
  const state = await setup(page)
  await visit(page)
  await page.getByRole('button', { name: '이름 수정', exact: true }).click()
  await page
    .getByRole('textbox', { name: '플레이리스트 이름' })
    .fill('저장하지 않을 이름')
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL(path)
  await expect(
    page.getByRole('heading', { name: '밤에 듣는 목소리', exact: true }),
  ).toBeVisible()
  expect(state.writes).toHaveLength(0)
})

for (const theme of ['light', 'dark']) {
  test(`관리 화면의 ${theme} 테마와 정상·이름 수정·삭제·빈 상태·로딩·오류 화면을 확인한다`, async ({
    page,
  }, info) => {
    const state = await setup(page)
    await page.addInitScript(
      (value) => localStorage.setItem('stelody-theme', value),
      theme,
    )
    async function capture(name: string) {
      await page.evaluate(() => document.fonts.ready)
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true)
      const folder = process.env.PLAYLIST_VISUAL_DIR
      if (folder)
        await page.screenshot({
          path: `${folder}/stelody-management-${info.project.name}-${theme}-${name}.png`,
          fullPage: name !== 'delete',
        })
    }
    await visit(page)
    await capture('normal')
    await page.getByRole('button', { name: '이름 수정', exact: true }).click()
    await expect(
      page.getByRole('textbox', { name: '플레이리스트 이름' }),
    ).toHaveValue('밤에 듣는 목소리')
    await capture('rename')
    await page.getByRole('button', { name: '취소', exact: true }).click()
    await page.getByRole('button', { name: '목록 삭제', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await capture('delete')
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    state.items = []
    state.playlist.totalCount = 0
    state.playlist.availableCount = 0
    state.playlist.version++
    await page.reload()
    await expect(
      page.getByRole('heading', { name: '아직 담은 곡이 없습니다' }),
    ).toBeVisible()
    await capture('empty')
    state.items = structuredClone(initial)
    state.playlist.totalCount = 3
    state.playlist.availableCount = 2
    state.playlist.version++
    let release = () => {}
    state.pageGate = new Promise<void>((r) => {
      release = r
    })
    await page.reload()
    await expect(
      page.getByRole('status', { name: '플레이리스트 불러오는 중' }),
    ).toBeVisible()
    await capture('loading')
    release()
    await expect(rows(page)).toHaveCount(3)
    state.readFailure = 503
    await page.reload()
    await expect(
      page.getByRole('heading', { name: '불러오지 못했어요' }),
    ).toBeVisible()
    await capture('error')
  })
}
