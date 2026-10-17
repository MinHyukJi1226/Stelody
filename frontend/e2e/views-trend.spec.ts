import { test, expect } from './fixtures'
import { detail, song, makeViews } from './song-detail-fixtures'
import type { SongViews } from '../src/features/songs/detail-queries'

const values = [1000, 1000, null, 2000, 2500, 10500, 11200]
const views: SongViews = {
  ...makeViews(),
  startDate: '2026-10-11',
  endDate: '2026-10-17',
  latestViewCount: 11200,
  latestObservedAt: '2026-10-17T05:30:00Z',
  publishedAt: '2026-10-17T05:35:00Z',
  points: values.map((viewCount, i) => ({
    date: `2026-10-${i + 11}`,
    viewCount,
    observedAt: viewCount === null ? null : `2026-10-${i + 11}T05:30:00Z`,
    source: viewCount === null ? null : 'YOUTUBE',
  })),
}

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-17T06:00:00Z') })
  await page.route(`**/api/v1/songs/${song.id}`, (route) =>
    route.fulfill({ json: detail }),
  )
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({ json: views }),
  )
})

test('누적·일별 그래프와 표에서 결측·계산 불가·실제 0·오늘을 구분한다', async ({
  page,
}, testInfo) => {
  await page.goto(`/songs/${song.id}`)
  const section = page.getByRole('region', { name: '조회수 추이', exact: true })
  await expect(
    section.getByRole('radio', { name: '누적 조회수', exact: true }),
  ).toBeChecked()
  await expect(section.locator('.recharts-line-curve')).toHaveCount(1)
  await expect(
    section.getByText('총 조회수 11,200회', { exact: true }),
  ).toBeVisible()
  const table = section.getByRole('table', { name: '날짜별 데이터' })
  await expect(table.getByRole('columnheader')).toHaveText([
    '날짜(KST)',
    '누적 조회수',
    '일별 증가량',
  ])
  const dateRow = (date: string) =>
    table.getByRole('row').filter({
      has: page.getByRole('button', {
        name: `${date} 데이터 보기`,
        exact: true,
      }),
    })
  await expect(dateRow('2026-10-11').getByRole('cell').last()).toHaveText(
    '계산 불가',
  )
  await expect(dateRow('2026-10-12').getByRole('cell').last()).toHaveText('0회')
  await expect(dateRow('2026-10-13').getByRole('cell')).toHaveText([
    '미수집',
    '미수집',
  ])
  await expect(dateRow('2026-10-14').getByRole('cell').last()).toHaveText(
    '계산 불가',
  )
  await expect(dateRow('2026-10-17')).toContainText('오늘 · 집계 중')
  await expect(dateRow('2026-10-17')).toContainText('14:30 관측')
  await section.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  await expect(section.locator('.recharts-line-curve')).toHaveCount(0)
  await expect(section.locator('.daily-view-bar')).toHaveCount(3)
  const low = section.locator('.daily-view-bar[data-increase="500"]')
  const peak = section.locator('.daily-view-bar[data-increase="8000"]')
  await expect(peak).toBeVisible()
  const height =
    (await peak.boundingBox())!.height / (await low.boundingBox())!.height
  expect(height).toBeCloseTo(16, 0)
  const today = section.locator('.daily-view-bar[data-date="2026-10-17"]')
  await expect(today.locator('path')).toHaveAttribute('stroke-dasharray', '3 3')
  if (testInfo.project.name === 'mobile') await peak.tap()
  else
    await section
      .getByRole('button', { name: '2026-10-16 데이터 보기', exact: true })
      .click()
  const selected = section.getByRole('status', { name: '선택한 날짜 데이터' })
  await expect(selected).toContainText('일별 증가량 +8,000회')
  await expect(selected).toContainText('2026. 10. 16. 14:30 KST')
  await section.getByRole('radio', { name: '누적 조회수', exact: true }).check()
  await expect(selected).toContainText('누적 조회수 10,500회')
  const lastDot = section.locator('.recharts-line-dot').last()
  if (testInfo.project.name === 'mobile') await lastDot.tap()
  else
    await section
      .getByRole('button', { name: '2026-10-17 데이터 보기', exact: true })
      .click()
  await expect(selected).toContainText('오늘 · 집계 중')
  await section.getByRole('button', { name: '선택 해제', exact: true }).click()
  await expect(selected).toHaveCount(0)
  for (let i = 0; i < 2; i++) {
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    await page.getByRole('button', { name: /모드로 변경$/ }).click()
  }
})

test('마우스 호버 위치에서 누적·일별 값과 결측 상태를 표시하고 벗어나면 숨긴다', async ({
  page,
}, testInfo) => {
  test.skip(
    testInfo.project.name === 'mobile',
    '터치 선택은 공통 시나리오에서 검증',
  )
  await page.goto(`/songs/${song.id}`)
  const section = page.getByRole('region', { name: '조회수 추이', exact: true })
  const tooltip = section.getByRole('tooltip', { name: '날짜별 조회수 정보' })
  const chart = section.getByRole('img')
  async function hoverDate(index: number) {
    await chart.scrollIntoViewIfNeeded()
    const tick = await chart
      .locator(
        '.recharts-xAxis-tick-labels .recharts-cartesian-axis-tick-value',
      )
      .nth(index)
      .boundingBox()
    const bounds = (await chart.boundingBox())!
    await page.mouse.move(tick!.x + tick!.width / 2, bounds.y + 100)
  }
  await expect(chart.locator('.recharts-line-curve')).toBeVisible()
  await hoverDate(5)
  await expect(tooltip).toContainText('2026.10.16')
  await expect(tooltip).toContainText('누적 조회수 10,500회')
  await expect(tooltip).toContainText('2026. 10. 16. 14:30 KST')
  await expect(
    section.getByRole('status', { name: '선택한 날짜 데이터' }),
  ).toHaveCount(0)
  await hoverDate(2)
  await expect(tooltip).toContainText('누적 조회수 미수집')
  await expect(tooltip).toContainText('관측값 없음')
  await section
    .getByRole('heading', { name: '조회수 추이', exact: true })
    .hover()
  await expect(tooltip).not.toBeVisible()
  await section.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  for (const [index, value] of [
    [0, '계산 불가'],
    [1, '0회'],
    [2, '미수집'],
    [5, '+8,000회'],
    [6, '+700회'],
  ] as const) {
    await hoverDate(index)
    await expect(tooltip).toContainText(`일별 증가량 ${value}`)
  }
  await expect(tooltip).toContainText('오늘 · 집계 중')
  const tip = (await tooltip.boundingBox())!
  const bounds = (await chart.boundingBox())!
  expect(tip.x).toBeGreaterThanOrEqual(bounds.x)
  expect(tip.x + tip.width).toBeLessThanOrEqual(bounds.x + bounds.width)
  await page.getByRole('button', { name: /모드로 변경$/ }).click()
  await hoverDate(5)
  await expect(tooltip).toContainText('일별 증가량 +8,000회')
  await section
    .getByRole('heading', { name: '조회수 추이', exact: true })
    .hover()
  await expect(tooltip).not.toBeVisible()
})

test('종류 전환은 기간을 유지하고 추가 요청 없이 같은 데이터를 사용한다', async ({
  page,
}) => {
  const periods: number[] = []
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) => {
    const days = Number(new URL(route.request().url()).searchParams.get('days'))
    periods.push(days)
    return route.fulfill({ json: { ...views, days } })
  })
  await page.goto(`/songs/${song.id}`)
  await expect(page.getByRole('table', { name: '날짜별 데이터' })).toBeVisible()
  await page.getByRole('radio', { name: '최근 30일', exact: true }).check()
  await expect.poll(() => periods.length).toBe(2)
  await expect(page.getByRole('table', { name: '날짜별 데이터' })).toBeVisible()
  await page.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  await expect(page.locator('.daily-view-bar')).toHaveCount(3)
  await expect(
    page.getByRole('radio', { name: '최근 30일', exact: true }),
  ).toBeChecked()
  await page.getByRole('radio', { name: '누적 조회수', exact: true }).check()
  await expect(
    page.getByRole('radio', { name: '최근 30일', exact: true }),
  ).toBeChecked()
  expect(periods).toEqual([7, 30])
  await page.getByRole('radio', { name: '최근 7일', exact: true }).check()
  await expect(
    page.getByRole('radio', { name: '최근 7일', exact: true }),
  ).toBeChecked()
  await expect(page.getByRole('table', { name: '날짜별 데이터' })).toBeVisible()
})

test('통계 오류·로딩 중에도 마지막 총 조회수를 유지하고 현재 종류로 복구한다', async ({
  page,
}) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let fail = true
  await page.route(`**/api/v1/songs/${song.id}/views?*`, async (route) => {
    await gate
    await (fail
      ? route.fulfill({
          status: 400,
          json: { code: 'INVALID_STATISTICS_QUERY' },
        })
      : route.fulfill({ json: views }))
  })
  try {
    await page.goto(`/songs/${song.id}`)
    await expect(
      page.getByRole('status', { name: '조회수 불러오는 중' }),
    ).toBeVisible()
    await expect(
      page.getByText('총 조회수 124,800회', { exact: true }),
    ).toBeVisible()
    await page.getByRole('radio', { name: '일별 증가량', exact: true }).check()
    await expect(
      page.getByRole('status', { name: '조회수 불러오는 중' }),
    ).toBeVisible()
    release()
    await expect(
      page.getByText('조회수 추이를 불러오지 못했어요', { exact: true }),
    ).toBeVisible()
    await expect(
      page.getByText('총 조회수 124,800회', { exact: true }),
    ).toBeVisible()
    fail = false
    await page.getByRole('button', { name: '다시 시도', exact: true }).click()
    await expect(page.locator('.daily-view-bar')).toHaveCount(3)
    await expect(
      page.getByRole('radio', { name: '일별 증가량', exact: true }),
    ).toBeChecked()
  } finally {
    release()
  }
})

test('0 증가만 있는 경우와 계산 불가·미수집만 있는 경우를 구분한다', async ({
  page,
}) => {
  let kind: 'zero' | 'unavailable' | 'missing' = 'zero'
  await page.route(`**/api/v1/songs/${song.id}/views?*`, (route) =>
    route.fulfill({
      json: {
        ...views,
        points: views.points.map((p, i) => ({
          ...p,
          viewCount:
            kind === 'zero'
              ? 1000
              : kind === 'unavailable' && i === 0
                ? 1000
                : null,
        })),
      },
    }),
  )
  await page.goto(`/songs/${song.id}`)
  await page.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  await expect(
    page.getByRole('table', { name: '날짜별 데이터' }).getByRole('row'),
  ).toHaveCount(8)
  await expect(page.locator('.daily-view-bar')).toHaveCount(0)
  await expect(
    page.getByRole('img', { name: /일별 증가량 막대그래프/ }),
  ).toBeVisible()
  kind = 'unavailable'
  await page.reload()
  await page.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  await expect(
    page.getByText('증가량을 계산할 데이터가 없습니다', { exact: true }),
  ).toBeVisible()
  kind = 'missing'
  await page.reload()
  await page.getByRole('radio', { name: '일별 증가량', exact: true }).check()
  await expect(
    page.getByText('기간 내 수집된 데이터가 없습니다', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('img', { name: /일별 증가량 막대그래프/ }),
  ).toHaveCount(0)
})
