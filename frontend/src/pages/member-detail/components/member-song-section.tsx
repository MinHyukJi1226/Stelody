import { useEffect, useId, useState, type ReactNode } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { memberSongsOptions } from '@/features/members/queries'
import type { MemberSongFilters } from '@/features/members/song-filters'
import { uniqueSongs } from '@/features/songs/queries'
import {
  CompactSongGrid,
  CompactSongSkeleton,
} from '@/features/songs/compact-song-grid'
import { HomeCarousel } from '@/pages/home/components/home-song-carousel'
import { CatalogExpansion } from '@/pages/song-catalog/catalog-expansion'
import { useMediaQuery } from '@/shared/lib/use-media-query'
import { ApiError } from '@/shared/api/errors'
import { StatePanel } from '@/shared/ui/state-panel'
import { Button } from '@/shared/ui/button'

const grid = 'grid-cols-2 gap-x-4 md:grid-cols-3 lg:grid-cols-6 md:gap-x-6'
export function MemberSongSection({
  memberId,
  label,
  filters,
  controls,
}: {
  memberId: string
  label: string
  filters: MemberSongFilters
  controls?: ReactNode
}) {
  const options = memberSongsOptions(memberId, filters)
  const query = useInfiniteQuery({
    ...options,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
  const client = useQueryClient()
  const tablet = useMediaQuery('(min-width:768px)')
  const desktop = useMediaQuery('(min-width:1024px)')
  const pageSize = desktop ? 6 : tablet ? 3 : 2
  const [expanded, setExpanded] = useState(false)
  const [position, setPosition] = useState({ size: pageSize, page: 0 })
  const [epoch, setEpoch] = useState(0)
  const id = useId()
  const songs = uniqueSongs(query.data?.pages ?? [])
  const pageCount = Math.ceil(songs.length / pageSize)
  const currentPage = Math.min(
    position.size === pageSize ? position.page : 0,
    Math.max(0, pageCount - 1),
  )
  const canExpand =
    currentPage < pageCount - 1 || (query.hasNextPage && !query.isError)
  const needsBuffer =
    currentPage > 0 && songs.length < (currentPage + 4) * pageSize
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  useEffect(() => {
    if ((expanded || needsBuffer) && hasNextPage && !isFetching && !isError)
      void fetchNextPage({ cancelRefetch: false })
  }, [expanded, needsBuffer, hasNextPage, isFetching, isError, fetchNextPage])
  const changed =
    query.error instanceof ApiError &&
    ['VIEW_PUBLICATION_CHANGED', 'INVALID_CATALOG_QUERY'].includes(
      query.error.code ?? '',
    )
  const unavailable =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  const retry = () => {
    if (changed) {
      setPosition({ size: pageSize, page: 0 })
      setEpoch((n) => n + 1)
      void client.resetQueries({ queryKey: options.queryKey, exact: true })
    } else
      void (query.isFetchNextPageError
        ? query.fetchNextPage()
        : query.refetch())
  }
  async function preparePage(target: number) {
    let data = query.data
    let hasNext = query.hasNextPage
    while (
      uniqueSongs(data?.pages ?? []).length < (target + 1) * pageSize &&
      hasNext
    ) {
      const count = data?.pages.length ?? 0
      const result = await query.fetchNextPage({ cancelRefetch: false })
      if (result.isError || !result.data || result.data.pages.length <= count)
        return false
      data = result.data
      hasNext = result.hasNextPage
    }
    return uniqueSongs(data?.pages ?? []).length > target * pageSize
  }
  return (
    <section
      aria-label={`${label} 곡 목록`}
      className={`flex min-w-0 flex-col gap-3${query.isPending ? '' : ' home-section-enter'}`}
    >
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-lg font-bold leading-[1.5]">
          {label}
          {query.data && !unavailable
            ? ` ${query.data.pages[0]!.totalCount.toLocaleString('ko-KR')}곡`
            : ''}
        </h2>
        {(expanded || canExpand) && (
          <Button
            size="icon"
            className="shrink-0 border-0 bg-transparent"
            aria-label={`${label} 곡 ${expanded ? '접기' : '모두 펼치기'}`}
            aria-expanded={expanded}
            aria-controls={id}
            onClick={() => {
              setPosition({ size: pageSize, page: 0 })
              setExpanded((v) => !v)
            }}
          >
            <svg
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
              className={`transition-transform motion-reduce:transition-none ${expanded ? 'rotate-90' : ''}`}
            >
              <path
                d="m9 5 7 7-7 7"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </Button>
        )}
      </div>
      {controls}
      <div id={id} aria-busy={expanded && query.isFetching} className="min-w-0">
        <CatalogExpansion>
          {unavailable ? (
            <StatePanel
              title="공개 곡을 볼 수 없습니다"
              description="멤버의 공개 상태를 확인할 수 없습니다."
            />
          ) : query.isPending ? (
            <CompactSongSkeleton count={6} className={grid} />
          ) : !query.data && query.isError ? (
            <StatePanel title="노래를 불러오지 못했어요" onRetry={retry} />
          ) : songs.length === 0 ? (
            <StatePanel title="등록된 공개 곡이 없습니다" />
          ) : expanded ? (
            <>
              <CompactSongGrid songs={songs} className={grid} />
              {query.isFetching && (
                <p role="status" className="sr-only">
                  섹션의 곡을 모두 불러오는 중…
                </p>
              )}
            </>
          ) : (
            <HomeCarousel
              scrollable
              key={`${pageSize}-${epoch}`}
              label={label}
              pages={Array.from({ length: pageCount }, (_, index) => (
                <CompactSongGrid
                  key={index}
                  songs={songs.slice(index * pageSize, (index + 1) * pageSize)}
                  className={grid}
                />
              ))}
              hasMore={Boolean(query.hasNextPage) && !query.isError}
              loadingMore={
                query.isFetching && songs.length < (currentPage + 2) * pageSize
              }
              lastPageFull={songs.length % pageSize === 0}
              onBeforeNext={preparePage}
              onPageChange={(page) => setPosition({ size: pageSize, page })}
            />
          )}
        </CatalogExpansion>
      </div>
      {query.data && query.isError && !unavailable && (
        <StatePanel
          title="노래를 더 불러오지 못했습니다"
          description={
            changed
              ? '조회 조건 또는 조회수 집계가 바뀌었습니다. 다시 시도하면 이 섹션의 첫 페이지부터 불러옵니다.'
              : '이미 불러온 목록은 유지됩니다.'
          }
          onRetry={retry}
        />
      )}
    </section>
  )
}
