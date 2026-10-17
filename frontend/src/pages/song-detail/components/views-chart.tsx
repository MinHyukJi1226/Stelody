import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  Rectangle,
  ReferenceDot,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type BarShapeProps,
  type MouseHandlerDataParam,
} from 'recharts'
import type { ViewMetric, ViewRow } from '@/features/songs/view-series'
import { useMediaQuery } from '@/shared/lib/use-media-query'
import { ViewsTooltip } from './views-tooltip'

function DailyBar(props: BarShapeProps) {
  const row = props.payload as ViewRow | undefined
  if (!row || row.increase === null || row.increase === 0) return <g />
  return (
    <g
      data-date={row.date}
      data-increase={row.increase}
      className="daily-view-bar"
    >
      <Rectangle
        x={props.x}
        y={props.y}
        width={props.width}
        height={props.height}
        radius={4}
        fill={row.isToday ? 'var(--tint)' : 'var(--brand)'}
        stroke={row.isToday ? 'var(--brand)' : 'none'}
        strokeDasharray={row.isToday ? '3 3' : undefined}
      />
    </g>
  )
}

export function ViewsChart({
  rows,
  metric,
  selected,
  onSelect,
}: {
  rows: ViewRow[]
  metric: ViewMetric
  selected: string | null
  onSelect: (date: string) => void
}) {
  const desktop = useMediaQuery('(min-width: 768px)')
  const canHover = useMediaQuery('(hover: hover) and (pointer: fine)')
  function select(state: MouseHandlerDataParam) {
    if (canHover) return
    const index = state.activeTooltipIndex
    if (index == null) return
    const row = rows[Number(index)]
    if (row) onSelect(row.date)
  }
  return (
    <div
      role="img"
      aria-label={
        metric === 'daily'
          ? '날짜별 YouTube 일별 증가량 막대그래프. 미수집·계산 불가는 막대를 표시하지 않습니다.'
          : '날짜별 YouTube 누적 조회수 선 그래프. 관측이 없는 날은 선이 끊깁니다.'
      }
      className="h-[278px] w-full min-w-0 md:h-[304px]"
    >
      <ResponsiveContainer
        width="100%"
        height="100%"
        minWidth={0}
        initialDimension={{ width: 318, height: 278 }}
      >
        <ComposedChart
          data={rows}
          margin={{ top: desktop ? 30 : 24, right: 12, bottom: 26, left: 0 }}
          accessibilityLayer
          onClick={select}
        >
          <CartesianGrid vertical={false} stroke="var(--line)" />
          <XAxis
            dataKey="date"
            tickFormatter={(date: string) => date.slice(5).replace('-', '.')}
            tick={{ fill: 'var(--muted)', fontSize: desktop ? 12 : 10 }}
            axisLine={false}
            tickLine={false}
            tickMargin={metric === 'daily' ? 28 : 8}
            minTickGap={desktop ? 20 : 4}
            interval={rows.length <= 7 ? 0 : 'preserveStartEnd'}
            height={38}
          />
          <YAxis
            domain={
              metric === 'daily'
                ? [
                    (minimum) => Math.min(0, minimum),
                    (maximum) => Math.max(1, maximum),
                  ]
                : ['auto', 'auto']
            }
            tick={{ fill: 'var(--muted)', fontSize: 12 }}
            tickFormatter={(value: number) =>
              Math.abs(value) >= 100000
                ? new Intl.NumberFormat('ko-KR', {
                    notation: 'compact',
                    maximumFractionDigits: 1,
                  }).format(value)
                : value.toLocaleString('ko-KR')
            }
            width={desktop ? 76 : 58}
            allowDecimals={false}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip
            trigger={canHover ? 'hover' : 'click'}
            filterNull={false}
            isAnimationActive={false}
            cursor={
              canHover
                ? { stroke: 'var(--subtle)', strokeDasharray: '3 3' }
                : false
            }
            content={({ active, label }) =>
              canHover && active ? (
                <ViewsTooltip
                  row={rows.find((row) => row.date === label)}
                  metric={metric}
                />
              ) : null
            }
          />
          {selected && (
            <ReferenceLine
              x={selected}
              stroke="var(--subtle)"
              strokeDasharray="3 3"
            />
          )}
          {metric === 'daily' ? (
            <>
              <ReferenceLine y={0} stroke="var(--muted)" />
              <Bar
                dataKey="increase"
                shape={DailyBar}
                isAnimationActive={false}
              />
              {rows.map((row) =>
                row.increase === null || row.increase === 0 ? (
                  <ReferenceDot
                    key={row.date}
                    x={row.date}
                    y={0}
                    r={row.increase === 0 ? 2 : 0}
                    fill="var(--muted)"
                    stroke="none"
                    label={
                      rows.length <= 7
                        ? {
                            value:
                              row.increase === 0
                                ? '0회'
                                : row.increaseStatus === 'missing'
                                  ? '×'
                                  : '?',
                            position: 'bottom',
                            offset: 8,
                            fill: 'var(--muted)',
                            fontSize: 10,
                          }
                        : false
                    }
                  />
                ) : null,
              )}
            </>
          ) : (
            <Line
              type="linear"
              dataKey="viewCount"
              stroke="var(--brand)"
              strokeWidth={3}
              dot={{ r: 3, fill: 'var(--brand)' }}
              connectNulls={false}
              isAnimationActive={false}
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}
