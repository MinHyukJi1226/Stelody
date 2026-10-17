import type { ViewMetric, ViewRow } from '@/features/songs/view-series'
import { formatIncrease, formatViewCount } from '@/features/songs/view-series'
import { formatObservedAt } from '../format'
import { Button } from '@/shared/ui/button'

export function ViewsSelection({
  row,
  metric,
  onClear,
}: {
  row: ViewRow
  metric: ViewMetric
  onClear: () => void
}) {
  return (
    <div
      role="status"
      aria-label="선택한 날짜 데이터"
      className="flex flex-col gap-1 rounded-xl bg-tint p-3 md:p-4"
    >
      <p className="text-sm">
        {row.date.replaceAll('-', '.')}
        {row.isToday ? ' · 오늘 · 집계 중' : ''}
      </p>
      <p className="text-base font-bold">
        {metric === 'daily'
          ? `일별 증가량 ${formatIncrease(row)}`
          : `누적 조회수 ${formatViewCount(row.viewCount)}`}
      </p>
      <p className="text-xs text-muted">
        {row.observedAt
          ? `관측 ${formatObservedAt(row.observedAt)}`
          : '관측값 없음'}
      </p>
      <Button
        onClick={onClear}
        className="min-h-11 self-start bg-transparent px-4 py-2 text-brand"
      >
        선택 해제
      </Button>
    </div>
  )
}
