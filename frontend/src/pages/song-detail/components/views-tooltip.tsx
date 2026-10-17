import {
  formatIncrease,
  formatViewCount,
  type ViewMetric,
  type ViewRow,
} from '@/features/songs/view-series'
import { formatObservedAt } from '../format'

export function ViewsTooltip({
  row,
  metric,
}: {
  row: ViewRow | undefined
  metric: ViewMetric
}) {
  if (!row) return null
  return (
    <div
      role="tooltip"
      aria-label="날짜별 조회수 정보"
      className="flex max-w-60 flex-col gap-1 rounded-xl border border-line bg-surface p-3 text-ink shadow-lg"
    >
      <p className="text-sm">
        {row.date.replaceAll('-', '.')}
        {row.isToday ? ' · 오늘 · 집계 중' : ''}
      </p>
      <p className="text-sm font-bold">
        {metric === 'daily'
          ? `일별 증가량 ${formatIncrease(row)}`
          : `누적 조회수 ${formatViewCount(row.viewCount)}`}
      </p>
      <p className="text-xs text-muted">
        {row.observedAt
          ? `관측 ${formatObservedAt(row.observedAt)}`
          : '관측값 없음'}
      </p>
    </div>
  )
}
