import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  songViewsOptions,
  type SongViews as Views,
  type ViewPeriod,
} from '@/features/songs/detail-queries'
import {
  formatViewCount,
  viewRows,
  type ViewMetric,
} from '@/features/songs/view-series'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { formatObservedAt } from '../format'
import { ViewsChart } from './views-chart'
import { ViewsControls } from './views-controls'
import { ViewsDataTable } from './views-data-table'
import { ViewsSelection } from './views-selection'
import { ViewsSkeleton } from './views-skeleton'

export function SongViews({
  songId,
  videoId,
  viewCount,
  observedAt,
  onVideoChanged,
}: {
  songId: string
  videoId: string
  viewCount: number | null
  observedAt: string | null
  onVideoChanged: () => void
}) {
  const [days, setDays] = useState<ViewPeriod>(7)
  const [metric, setMetric] = useState<ViewMetric>('cumulative')
  const [selected, setSelected] = useState<string | null>(null)
  const client = useQueryClient()
  const query = useQuery(songViewsOptions(songId, videoId, days))
  const data = query.data?.videoId === videoId ? query.data : undefined
  // Retain the last confirmed total while loading another range; never reuse its chart rows.
  const otherRange = client.getQueryData<Views>(
    songViewsOptions(songId, videoId, days === 7 ? 30 : 7).queryKey,
  )
  const summary =
    data ?? (otherRange?.videoId === videoId ? otherRange : undefined)
  const rows = data ? viewRows(data.points) : []
  const hasObservations = rows.some((row) => row.viewCount !== null)
  const hasIncreases = rows.some((row) => row.increase !== null)
  const selectedRow = rows.find((row) => row.date === selected)
  const loading = query.isPending
  const mismatch = query.data && query.data.videoId !== videoId
  const initialError = !data && query.isError
  const showRows = !loading && !mismatch && hasObservations
  return (
    <section
      aria-labelledby="views-title"
      className="flex min-w-0 flex-col gap-4 rounded-2xl bg-surface p-4 md:p-6"
    >
      <h2 id="views-title" className="text-2xl font-bold leading-[1.5]">
        조회수 추이
      </h2>
      <div className="flex flex-col gap-1">
        <p className="break-words text-[32px] font-bold leading-[1.5]">
          총 조회수{' '}
          {formatViewCount(summary ? summary.latestViewCount : viewCount)}
        </p>
        <p className="text-xs leading-[1.5] text-muted">
          현재 영상의 전체 누적값 · 선택 기간의 증가량이 아닙니다.
        </p>
        <p className="text-xs leading-[1.5] text-muted">
          수집 시작{' '}
          {summary?.collectionStartedAt
            ? formatObservedAt(summary.collectionStartedAt)
            : '미확인'}{' '}
          · 최종 관측{' '}
          {formatObservedAt(summary ? summary.latestObservedAt : observedAt)}
        </p>
        <p className="text-xs leading-[1.5] text-muted">
          갱신{' '}
          {summary?.publishedAt
            ? formatObservedAt(summary.publishedAt)
            : '시각 미확인'}
        </p>
      </div>
      <ViewsControls
        metric={metric}
        days={days}
        onMetric={setMetric}
        onDays={(value) => {
          setDays(value)
          setSelected(null)
        }}
      />
      <p className="text-sm font-medium text-muted">
        {metric === 'daily' ? '일별 증가량 (회) · 0 기준' : '누적 조회수 (회)'}
      </p>
      {loading ? (
        <ViewsSkeleton />
      ) : mismatch ? (
        <StatePanel
          title="대표 영상이 변경되었습니다"
          description="곡 정보를 다시 불러와 조회수를 확인해 주세요."
          onRetry={onVideoChanged}
        />
      ) : initialError ? (
        <div
          role="status"
          className="flex h-[278px] flex-col justify-center gap-3 rounded-xl bg-bg p-6 md:h-[304px]"
        >
          <p className="font-bold">조회수 추이를 불러오지 못했어요</p>
          <p className="text-sm text-muted">
            총 조회수는 마지막으로 확인한 값입니다. 다시 시도해 주세요.
          </p>
          <Button
            className="min-h-11 self-start bg-tint text-brand"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </Button>
        </div>
      ) : !hasObservations || (metric === 'daily' && !hasIncreases) ? (
        <div
          role="status"
          className="flex h-[278px] flex-col justify-center gap-3 rounded-xl bg-bg p-6 md:h-[304px]"
        >
          <p className="font-bold">
            {!hasObservations
              ? '기간 내 수집된 데이터가 없습니다'
              : '증가량을 계산할 데이터가 없습니다'}
          </p>
          <p className="text-sm text-muted">
            {!hasObservations
              ? '관측값이 수집되면 그래프와 날짜별 데이터를 표시합니다.'
              : '전날과 해당 날짜의 관측값이 모두 있어야 계산할 수 있습니다.'}
          </p>
        </div>
      ) : (
        <>
          {query.isError && (
            <StatePanel
              title="조회수를 갱신하지 못했습니다"
              description="이전에 불러온 데이터를 표시하고 있습니다."
              onRetry={() => void query.refetch()}
            />
          )}
          <ViewsChart
            rows={rows}
            metric={metric}
            selected={selected}
            onSelect={setSelected}
          />
          <p className="text-xs leading-[1.5] text-muted">
            {metric === 'daily' ? (
              <>
                × 미수집 · ? 계산 불가 · 0회 실제 증가량 0<br />
                옅은 막대는 오늘 · 집계 중 데이터입니다.
              </>
            ) : (
              '한국 시간(KST) 기준 · 미수집 구간은 선을 연결하지 않습니다.'
            )}
          </p>
          <p className="text-xs leading-[1.5] text-muted">
            그래프에 마우스를 올리거나 터치해 값을 확인하세요. 표의 날짜로도
            확인할 수 있습니다.
          </p>
        </>
      )}
      {!loading && !mismatch && selectedRow && (
        <ViewsSelection
          row={selectedRow}
          metric={metric}
          onClear={() => setSelected(null)}
        />
      )}
      <ViewsDataTable
        rows={showRows ? rows : []}
        selected={selected}
        onSelect={setSelected}
        loading={loading}
        error={initialError}
      />
    </section>
  )
}
