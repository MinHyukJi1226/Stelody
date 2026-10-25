import { test, expect, makeSong } from './fixtures'
import { mockAdmin } from './admin-fixtures'

const snapshot = {
  items: [
    { id: makeSong(993).id, version: 2 },
    { id: makeSong(994).id, version: 4 },
  ],
  hasMore: false,
}

test('검토 완료는 전체 대상 확인 후 요청하고 성공 시 목록을 새로 조회한다', async ({
  page,
}, info) => {
  const state = await mockAdmin(page)
  let posts = 0
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' },
    }),
  )
  await page.route('**/api/v1/admin/reviews/completion', async (route) => {
    if (route.request().method() === 'GET')
      return route.fulfill({ json: snapshot })
    posts++
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    expect(route.request().postDataJSON()).toEqual({ items: snapshot.items })
    state.empty = true
    await route.fulfill({ json: { deletedCount: 2 } })
  })
  await page.goto('/admin/reviews')
  await page.getByRole('button', { name: '검토 완료', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('2건', { exact: true })).toBeVisible()
  await expect(
    dialog.getByText(
      '등록된 곡과 이 창을 연 뒤 새로 들어온 후보는 유지됩니다.',
    ),
  ).toBeVisible()
  expect(posts).toBe(0)
  expect(
    await dialog.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
  ).toBe(true)
  await page.screenshot({
    path: info.outputPath('review-completion.png'),
    fullPage: true,
  })
  await dialog.getByRole('button', { name: '미등록 후보 삭제' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText('신규 검토 대상이 없습니다.')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '검토 완료', exact: true }),
  ).toBeDisabled()
  expect(posts).toBe(1)
})

test('응답 유실 후에는 새 후보를 가져오지 않고 같은 대상만 재요청한다', async ({
  page,
}) => {
  await mockAdmin(page)
  const bodies: unknown[] = []
  let previews = 0
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' },
    }),
  )
  await page.route('**/api/v1/admin/reviews/completion', async (route) => {
    if (route.request().method() === 'GET') {
      previews++
      return route.fulfill({ json: snapshot })
    }
    bodies.push(route.request().postDataJSON())
    if (bodies.length === 1) return route.abort('failed')
    return route.fulfill({ json: { deletedCount: 0 } })
  })
  await page.goto('/admin/reviews')
  await page.getByRole('button', { name: '검토 완료', exact: true }).click()
  const submit = page
    .getByRole('dialog')
    .getByRole('button', { name: '미등록 후보 삭제' })
  await expect(submit).toBeEnabled()
  await submit.click()
  await expect(page.getByRole('alert')).toContainText('같은 대상')
  await submit.click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(previews).toBe(1)
  expect(bodies).toEqual([{ items: snapshot.items }, { items: snapshot.items }])
})

test('후보 변경 충돌 시 자동 재전송하지 않고 관리자가 대상을 다시 확인한다', async ({
  page,
}) => {
  await mockAdmin(page)
  let previews = 0,
    posts = 0
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' },
    }),
  )
  await page.route('**/api/v1/admin/reviews/completion', async (route) => {
    if (route.request().method() === 'GET') {
      previews++
      return route.fulfill({
        json: {
          ...snapshot,
          items: previews === 1 ? snapshot.items : [snapshot.items[0]],
        },
      })
    }
    posts++
    return route.fulfill({
      status: 409,
      json: { code: 'REVIEW_VERSION_CONFLICT' },
    })
  })
  await page.goto('/admin/reviews')
  await page.getByRole('button', { name: '검토 완료', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '미등록 후보 삭제' }).click()
  await expect(dialog.getByRole('alert')).toContainText('후보가 변경되어')
  await expect(
    dialog.getByRole('button', { name: '미등록 후보 삭제' }),
  ).toBeDisabled()
  expect(previews).toBe(1)
  expect(posts).toBe(1)
  await dialog.getByRole('button', { name: '대상 다시 확인' }).click()
  await expect(dialog.getByText('1건', { exact: true })).toBeVisible()
  await expect(
    dialog.getByRole('button', { name: '미등록 후보 삭제' }),
  ).toBeEnabled()
  expect(posts).toBe(1)
})
