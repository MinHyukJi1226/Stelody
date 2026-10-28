import { test, expect, type Page } from '@playwright/test'
import { fixture, cid } from './original-artist-fixtures'
import { editPath } from './admin-song-fixtures'
import { admin } from './admin-fixtures'
import type { ArtistReceipt } from '../src/features/admin/artist-registration-receipt'
const modal = (page: Page) => page.getByRole('dialog')
const storageKey = `stelody-artist-registration:${admin.id}`
async function stored(page: Page) {
  return page.evaluate((key) => {
    const raw = sessionStorage.getItem(key)
    return raw ? (JSON.parse(raw) as ArtistReceipt) : null
  }, storageKey)
}
async function openNew(page: Page) {
  await page.goto(editPath)
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await modal(page).getByRole('button', { name: '아티스트 찾기·추가' }).click()
  await modal(page).getByRole('button', { name: '새 아티스트 등록' }).click()
}
async function fill(page: Page) {
  await modal(page).getByLabel('아티스트 이름*').fill('등록 복구 테스트')
  await modal(page).getByLabel('검색 별칭 · 선택').fill('별칭 B')
  await modal(page).getByRole('button', { name: '별칭 추가' }).click()
  await modal(page).getByLabel('검색 별칭 · 선택').fill('별칭 A')
  await modal(page).getByRole('button', { name: '별칭 추가' }).click()
  await modal(page).getByLabel('변경 사유*').fill('원곡 크레딧 확인')
}
async function submit(page: Page) {
  await modal(page)
    .getByRole('button', { name: '아티스트 등록', exact: true })
    .click()
}
test('전송 전에 UUID·본문을 보관하고 새로고침 후 같은 내용으로만 재전송한다', async ({
  page,
}, info) => {
  const state = await fixture(page)
  state.createBehavior = 'notReceived'
  let atPost: ArtistReceipt | null = null
  await page.route('**/api/v1/admin/artists', async (route) => {
    if (route.request().method() === 'POST') atPost = await stored(page)
    return route.fallback()
  })
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '등록 접수 기록이 없어요' }),
  ).toBeVisible()
  for (const theme of ['light', 'dark']) {
    await page.evaluate((value) => {
      document.documentElement.dataset.theme = value
    }, theme)
    const box = await modal(page).boundingBox()
    const viewport = page.viewportSize()!
    expect(box!.width).toBeLessThanOrEqual(viewport.width - 30)
    expect(box!.height).toBeLessThanOrEqual(viewport.height - 30)
    expect(
      await modal(page).evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true)
    await page.screenshot({
      path: `test-results/artist-registration-${info.project.name}-${theme}.png`,
      animations: 'disabled',
    })
  }
  const original = await stored(page)
  expect(original?.requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(atPost).toEqual(original)
  expect(original?.body.aliases).toEqual(['별칭 B', '별칭 A'])
  expect(state.registrations).toHaveLength(1)
  await openNew(page)
  await expect(
    modal(page).getByRole('heading', { name: '등록 접수 기록이 없어요' }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
  expect(await stored(page)).toEqual(original)
  state.createBehavior = 'normal'
  await modal(page).getByRole('button', { name: '같은 요청 다시 전송' }).click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.registrations).toEqual([
    { key: original!.requestId, body: original!.body },
    { key: original!.requestId, body: original!.body },
  ])
  expect(await stored(page)).toBe(null)
  expect(state.workPuts).toHaveLength(0)
})
test('처리 중은 3초 간격으로 조회하고 완료된 ID를 가져오며 POST를 반복하지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.createBehavior = 'processing'
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '아티스트를 등록하고 있어요' }),
  ).toBeVisible()
  await expect
    .poll(() => state.lookupCalls.length, { timeout: 8000 })
    .toBeGreaterThanOrEqual(2)
  expect(state.registrations).toHaveLength(1)
  const pending = (await stored(page))!
  state.artists[cid] = {
    id: cid,
    version: 0,
    name: '등록 뒤 정정된 현재 이름',
    aliases: [],
  }
  state.receipts.set(pending.requestId, {
    body: pending.body,
    status: 'COMPLETED',
  })
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible({ timeout: 8000 })
  await modal(page).getByRole('button', { name: '선택 적용' }).click()
  await expect(
    modal(page).getByRole('button', {
      name: '등록 뒤 정정된 현재 이름 선택 제거',
    }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
})
test('응답 유실 뒤 조회 503과 잘못된 완료 응답은 미접수로 바꾸지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.mode = 'lost'
  state.lookupError = 503
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '등록 결과를 확인하지 못했어요' }),
  ).toBeVisible()
  await expect(
    modal(page).getByRole('button', { name: '같은 요청 다시 전송' }),
  ).toHaveCount(0)
  const original = await stored(page)
  state.lookupError = 0
  state.lookupMalformed = true
  await modal(page).getByRole('button', { name: '등록 결과 다시 확인' }).click()
  await expect(
    modal(page).getByRole('heading', { name: '등록 결과를 확인하지 못했어요' }),
  ).toBeVisible()
  expect(await stored(page)).toEqual(original)
  expect(state.registrations).toHaveLength(1)
  state.lookupMalformed = false
  await modal(page).getByRole('button', { name: '등록 결과 다시 확인' }).click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
})
test('실패가 확인된 요청은 수정 후 명시적으로 새 UUID로 접수한다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.createBehavior = 'failed'
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '아티스트를 등록하지 못했어요' }),
  ).toBeVisible()
  const original = await stored(page)
  await expect(
    modal(page).getByRole('button', { name: '같은 요청 다시 전송' }),
  ).toHaveCount(0)
  await modal(page).getByRole('button', { name: '입력 수정' }).click()
  await expect(modal(page).getByLabel('변경 사유*')).toHaveValue(
    '원곡 크레딧 확인',
  )
  await modal(page).getByLabel('아티스트 이름*').fill('수정한 등록명')
  state.createBehavior = 'normal'
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.registrations[1]!.key).not.toBe(original!.requestId)
  expect(state.registrations[1]!.body.name).toBe('수정한 등록명')
})
test('접수 전 입력 오류는 오류와 입력을 보존하고 수정 후 새 요청으로 보낸다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.mode = 'validation'
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(modal(page).getByText('별칭을 확인해주세요')).toBeVisible()
  await expect(
    modal(page).getByRole('heading', { name: '등록 접수 기록이 없어요' }),
  ).toBeVisible()
  const original = await stored(page)
  await modal(page).getByRole('button', { name: '입력 수정' }).click()
  await expect(modal(page).getByLabel('아티스트 이름*')).toHaveValue(
    '등록 복구 테스트',
  )
  await modal(page).getByRole('button', { name: '별칭 B 별칭 삭제' }).click()
  state.mode = 'success'
  await submit(page)
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.registrations[1]!.key).not.toBe(original!.requestId)
  expect(state.registrations[1]!.body.aliases).toEqual(['별칭 A'])
})
test('만료된 결과는 새 요청으로 반복하지 않고 같은 ID를 보관한다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.mode = 'lost'
  state.lookupError = 410
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', {
      name: '등록 결과 확인 기간이 만료됐어요',
    }),
  ).toBeVisible()
  const original = await stored(page)
  await expect(
    modal(page).getByRole('button', { name: '같은 요청 다시 전송' }),
  ).toHaveCount(0)
  await openNew(page)
  await expect(
    modal(page).getByRole('heading', {
      name: '등록 결과 확인 기간이 만료됐어요',
    }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
  expect(await stored(page)).toEqual(original)
})
test('같은 UUID의 본문 충돌은 새로고침 후에도 자동 조회·등록을 막는다', async ({
  page,
}) => {
  const state = await fixture(page)
  state.createBehavior = 'conflict'
  await openNew(page)
  await fill(page)
  await submit(page)
  await expect(
    modal(page).getByRole('heading', {
      name: '보관한 요청 내용이 일치하지 않아요',
    }),
  ).toBeVisible()
  expect((await stored(page))?.conflicted).toBe(true)
  await openNew(page)
  await expect(
    modal(page).getByRole('heading', {
      name: '보관한 요청 내용이 일치하지 않아요',
    }),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(1)
  expect(state.lookupCalls).toHaveLength(0)
})
test('탭 저장이 차단되면 POST하지 않는다', async ({ page }) => {
  const state = await fixture(page)
  await openNew(page)
  await fill(page)
  await page.evaluate(() => {
    Storage.prototype.setItem = () => {
      throw new Error('storage blocked')
    }
  })
  await submit(page)
  await expect(
    modal(page).getByText(/등록 요청을 보관할 수 없어 전송하지 않았습니다/),
  ).toBeVisible()
  expect(state.registrations).toHaveLength(0)
  expect(state.artistWrites).toHaveLength(0)
})
test('손상된 복구 자료는 지우거나 새 요청으로 대체하지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  await page.goto(editPath)
  await page.evaluate(
    (key) => sessionStorage.setItem(key, '{broken'),
    storageKey,
  )
  await openNew(page)
  await expect(
    modal(page).getByRole('heading', { name: '등록 요청을 복구할 수 없어요' }),
  ).toBeVisible()
  expect(
    await page.evaluate((key) => sessionStorage.getItem(key), storageKey),
  ).toBe('{broken')
  expect(state.registrations).toHaveLength(0)
  expect(state.lookupCalls).toHaveLength(0)
})
for (const status of [401, 403])
  test(`결과 조회 ${status}는 공통 권한 화면으로 보내고 원래 요청을 보존한다`, async ({
    page,
  }) => {
    const state = await fixture(page)
    state.mode = 'lost'
    state.lookupError = status
    await openNew(page)
    await fill(page)
    await submit(page)
    await expect(
      page.getByRole('heading', {
        name: status === 401 ? '로그인이 필요해요' : '관리자 권한이 없어요',
      }),
    ).toBeVisible()
    expect((await stored(page))?.requestId).toBe(state.registrations[0]!.key)
    expect(state.registrations).toHaveLength(1)
  })
test('CSRF 대기 중 세션 조회가 실패해도 원래 요청을 유지하고 POST를 보내지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  await openNew(page)
  await fill(page)
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
  await submit(page)
  await expect.poll(() => started).toBe(true)
  const original = await stored(page)
  state.song.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  const response = page.waitForResponse('**/api/v1/auth/csrf')
  release()
  await response
  state.song.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    modal(page).getByRole('heading', { name: '등록 접수 기록이 없어요' }),
  ).toBeVisible()
  expect(await stored(page)).toEqual(original)
  expect(state.registrations).toHaveLength(0)
})
