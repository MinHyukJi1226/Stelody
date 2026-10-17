import { useId } from 'react'
import type { ViewPeriod } from '@/features/songs/detail-queries'
import type { ViewMetric } from '@/features/songs/view-series'

const optionClass =
  'flex min-h-11 flex-1 cursor-pointer items-center justify-center rounded-lg px-2 text-sm font-medium text-muted peer-checked:bg-tint peer-checked:text-brand peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand'

export function ViewsControls({
  metric,
  days,
  onMetric,
  onDays,
}: {
  metric: ViewMetric
  days: ViewPeriod
  onMetric: (metric: ViewMetric) => void
  onDays: (days: ViewPeriod) => void
}) {
  const id = useId()
  return (
    <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between md:gap-3">
      <fieldset className="flex w-full gap-1 rounded-xl bg-bg p-1 md:w-[318px]">
        <legend className="sr-only">그래프 종류</legend>
        {(['cumulative', 'daily'] as const).map((value) => (
          <label key={value} className="relative min-w-0 flex-1">
            <input
              className="peer absolute inset-0 size-full cursor-pointer opacity-0"
              type="radio"
              name={`${id}-metric`}
              value={value}
              checked={metric === value}
              onChange={() => onMetric(value)}
            />
            <span className={optionClass}>
              {value === 'cumulative' ? '누적 조회수' : '일별 증가량'}
            </span>
          </label>
        ))}
      </fieldset>
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-muted md:hidden" aria-hidden="true">
          기간
        </span>
        <fieldset className="flex w-[234px] max-w-full gap-1 rounded-xl bg-bg p-1">
          <legend className="sr-only">조회수 조회 기간</legend>
          {([7, 30] as const).map((value) => (
            <label key={value} className="relative min-w-0 flex-1">
              <input
                className="peer absolute inset-0 size-full cursor-pointer opacity-0"
                type="radio"
                name={`${id}-days`}
                value={value}
                checked={days === value}
                onChange={() => onDays(value)}
              />
              <span className={optionClass}>최근 {value}일</span>
            </label>
          ))}
        </fieldset>
      </div>
    </div>
  )
}
