import type { SongViews } from './detail-queries'

export type ViewMetric = 'cumulative' | 'daily'
export type ViewRow = SongViews['points'][number] & {
  increase: number | null
  increaseStatus: 'observed' | 'missing' | 'unavailable'
  isToday: boolean
}

export const koreanToday = () =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())

export function viewRows(
  points: SongViews['points'],
  today = koreanToday(),
): ViewRow[] {
  const byDate = new Map(points.map((point) => [point.date, point]))
  return [...points]
    .sort((a, b) => a.date.localeCompare(b.date))
    .map((point) => {
      // Compare adjacent Korean calendar dates only; never bridge a collection gap.
      const previousDate = new Date(`${point.date}T00:00:00Z`)
      previousDate.setUTCDate(previousDate.getUTCDate() - 1)
      const previous = byDate.get(previousDate.toISOString().slice(0, 10))
      const status =
        point.viewCount === null
          ? 'missing'
          : previous?.viewCount == null
            ? 'unavailable'
            : 'observed'
      return {
        ...point,
        isToday: point.date === today,
        increaseStatus: status,
        increase:
          status === 'observed'
            ? point.viewCount! - previous!.viewCount!
            : null,
      }
    })
}

export function formatIncrease(
  row: Pick<ViewRow, 'increase' | 'increaseStatus'>,
): string {
  if (row.increaseStatus === 'missing') return '미수집'
  if (row.increase === null) return '계산 불가'
  return `${row.increase > 0 ? '+' : ''}${row.increase.toLocaleString('ko-KR')}회`
}

export const formatViewCount = (count: number | null) =>
  count === null ? '미수집' : `${count.toLocaleString('ko-KR')}회`
