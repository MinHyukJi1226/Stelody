import { expect, it } from 'vitest'
import { formatIncrease, viewRows } from './view-series'
import type { SongViews } from './detail-queries'

const point = (
  date: string,
  viewCount: number | null,
): SongViews['points'][number] => ({
  date,
  viewCount,
  observedAt: viewCount === null ? null : `${date}T12:00:00Z`,
  source: viewCount === null ? null : 'YOUTUBE',
})
it('전날 결측·조회 범위 첫날·실제 0을 구분하고 결측을 건너뛰어 계산하지 않는다', () => {
  const rows = viewRows(
    [
      point('2026-10-11', 1000),
      point('2026-10-12', 1000),
      point('2026-10-13', null),
      point('2026-10-14', 2000),
      point('2026-10-15', 2500),
      point('2026-10-16', 10500),
      point('2026-10-17', 11200),
    ],
    '2026-10-17',
  )
  expect(rows.map((r) => r.increase)).toEqual([
    null,
    0,
    null,
    null,
    500,
    8000,
    700,
  ])
  expect(rows.map(formatIncrease)).toEqual([
    '계산 불가',
    '0회',
    '미수집',
    '계산 불가',
    '+500회',
    '+8,000회',
    '+700회',
  ])
  expect(rows.filter((r) => r.isToday).map((r) => r.date)).toEqual([
    '2026-10-17',
  ])
})
it('날짜가 생략되면 마지막 관측값과 차이를 계산하지 않는다', () => {
  const rows = viewRows([point('2026-10-11', 1000), point('2026-10-13', 2000)])
  expect(rows[1]?.increaseStatus).toBe('unavailable')
})
it('월·연도 경계에서도 바로 이전 날짜로 계산하며 원본 순서를 변경하지 않는다', () => {
  const points = [point('2027-01-01', 1500), point('2026-12-31', 1000)]
  expect(viewRows(points).map((r) => r.increase)).toEqual([null, 500])
  expect(points[0]?.date).toBe('2027-01-01')
})
it('조회수 감소를 0이나 양수로 바꾸지 않는다', () => {
  const rows = viewRows([point('2026-10-11', 1000), point('2026-10-12', 900)])
  expect(formatIncrease(rows[1]!)).toBe('-100회')
})
it('관측값이 전부 없으면 증가량도 없고 오늘 여부는 실제 한국 날짜로만 판단한다', () => {
  const rows = viewRows(
    [point('2026-10-16', null), point('2026-10-17', null)],
    '2026-10-18',
  )
  expect(
    rows.every((r) => r.increase === null && r.increaseStatus === 'missing'),
  ).toBe(true)
  expect(rows.some((r) => r.isToday)).toBe(false)
  expect(viewRows([])).toEqual([])
})
