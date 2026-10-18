import { test, expect, emptyPage } from './fixtures'
import { member, graduate } from './member-fixtures'

test('멤버 목록은 서버 순서·졸업 표시를 유지하고 추가 페이지 중복을 제거한다', async ({
  page,
}) => {
  const cursors: (string | null)[] = []
  await page.route('**/api/v1/members?*', (route) => {
    const p = new URL(route.request().url()).searchParams
    expect(p.get('status')).toBe('ALL')
    cursors.push(p.get('cursor'))
    return route.fulfill({
      json: p.has('cursor')
        ? { items: [member, graduate], hasNext: false, nextCursor: null }
        : { items: [member], hasNext: true, nextCursor: 'members+/=&next' },
    })
  })
  await page.goto('/members')
  await expect(
    page.getByRole('heading', { name: '목소리로 찾아보세요' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '멤버 더 보기' }).click()
  const roster = page.getByLabel('모든 멤버', { exact: true })
  await expect(roster.getByRole('link')).toHaveCount(2)
  await expect(roster.getByRole('link').nth(0)).toHaveAccessibleName(
    member.name,
  )
  await expect(roster.getByRole('link').nth(1)).toHaveAccessibleName(
    `${graduate.name} (졸업)`,
  )
  expect(cursors).toEqual([null, 'members+/=&next'])
})

test('멤버 목록의 로딩·오류·재시도·빈 목록을 구분한다', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let fail = true
  await page.route('**/api/v1/members?*', async (route) => {
    await gate
    await route.fulfill(
      fail
        ? { status: 400, json: { code: 'INVALID_CATALOG_QUERY' } }
        : { json: emptyPage },
    )
  })
  try {
    await page.goto('/members')
    await expect(
      page.getByRole('status', { name: '멤버 불러오는 중' }),
    ).toBeVisible()
    release()
    await expect(
      page.getByText('멤버를 불러오지 못했습니다', { exact: true }),
    ).toBeVisible()
    fail = false
    await page.getByRole('button', { name: '다시 시도', exact: true }).click()
    await expect(
      page.getByText('등록된 멤버가 없습니다', { exact: true }),
    ).toBeVisible()
  } finally {
    release()
  }
})
