import {
  formatIncrease,
  formatViewCount,
  type ViewMetric,
  type ViewRow,
} from '@/features/songs/view-series'

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
      <p className="text-sm">{row.date.replaceAll('-', '.')}</p>
      <p className="text-sm font-bold">
        {metric === 'daily'
          ? formatIncrease(row)
          : formatViewCount(row.viewCount)}
      </p>
    </div>
  )
}
