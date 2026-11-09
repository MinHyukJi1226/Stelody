import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { uniqueSongs } from '@/features/songs/queries'
import {
  CompactSongGrid,
  CompactSongSkeleton,
} from '@/features/songs/compact-song-grid'
import { HomeCarousel } from '@/pages/home/components/home-song-carousel'
import { useMediaQuery } from '@/shared/lib/use-media-query'
import { StatePanel } from '@/shared/ui/state-panel'
import { Button } from '@/shared/ui/button'
import { ApiError } from '@/shared/api/errors'
import {
  catalogGroupsOptions,
  catalogGroupSongsOptions,
  groupSongsKey,
  type CatalogGroup,
  type CatalogGrouping,
  type CatalogSort,
} from './catalog-api'
import { CatalogLoadMore } from './catalog-list'
import { CatalogExpansion } from './catalog-expansion'

const rowGrid = 'grid-cols-2 gap-x-4 md:grid-cols-3 lg:grid-cols-6 md:gap-x-6'
function GroupSection({
  group,
  groupBy,
  sort,
  onRefresh,
}: {
  group: CatalogGroup
  groupBy: CatalogGrouping
  sort: CatalogSort
  onRefresh: () => void
}) {
  const [retryVersion, setRetryVersion] = useState(0)
  const client = useQueryClient()
  const options = catalogGroupSongsOptions(groupBy, sort, group)
  const query = useInfiniteQuery(options)
  const tablet = useMediaQuery('(min-width:768px)')
  const desktop = useMediaQuery('(min-width:1024px)')
  const pageSize = desktop ? 6 : tablet ? 3 : 2
  const [expanded, setExpanded] = useState(false)
  const [position, setPosition] = useState({ size: pageSize, page: 0 })
  const contentId = useId()
  const name =
    groupBy === 'ORIGINAL_ARTIST' && group.id === 'ORIGINAL'
      ? '스텔라이브 오리지널'
      : group.name
  const songs = uniqueSongs(query.data?.pages ?? [])
  const pageCount = Math.ceil(songs.length / pageSize)
  const currentPage = Math.min(
    position.size === pageSize ? position.page : 0,
    Math.max(0, pageCount - 1),
  )
  const canExpand =
    currentPage < pageCount - 1 || (query.hasNextPage && !query.isError)
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  const needsBuffer =
    currentPage > 0 && songs.length < (currentPage + 4) * pageSize
  useEffect(() => {
    if ((expanded || needsBuffer) && hasNextPage && !isFetching && !isError)
      void fetchNextPage({ cancelRefetch: false })
  }, [expanded, needsBuffer, hasNextPage, isFetching, isError, fetchNextPage])
  const changed =
    query.error instanceof ApiError &&
    (query.error.code === 'VIEW_PUBLICATION_CHANGED' ||
      query.error.code === 'INVALID_CATALOG_QUERY')
  const missing = query.error instanceof ApiError && query.error.status === 404
  async function prepareNextPage(targetPage: number) {
    let data = query.data
    let hasNext = query.hasNextPage
    const requiredCount = (targetPage + 1) * pageSize
    while (uniqueSongs(data?.pages ?? []).length < requiredCount && hasNext) {
      const previousPageCount = data?.pages.length ?? 0
      const result = await query.fetchNextPage({ cancelRefetch: false })
      if (result.isError || !result.data) return false
      data = result.data
      hasNext = result.hasNextPage
      if ((data?.pages.length ?? 0) <= previousPageCount) return false
    }
    return uniqueSongs(data?.pages ?? []).length > targetPage * pageSize
  }
  const retry = () => {
    if (missing) {
      onRefresh()
      return
    }
    if (changed) {
      setPosition({ size: pageSize, page: 0 })
      setRetryVersion((value) => value + 1)
      void client.resetQueries({ queryKey: options.queryKey, exact: true })
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  return (
    <section
      aria-label={`${name} 곡 목록`}
      className={`flex min-w-0 flex-col gap-4${query.isPending ? '' : ' home-section-enter'}`}
    >
      <div className="flex items-center justify-between gap-4">
        <h2 className="min-w-0 text-2xl font-bold leading-[1.5]">
          {groupBy === 'MEMBER' ? (
            <Link
              to={`/members/${encodeURIComponent(group.id)}`}
              title={`${name} 멤버 상세 보기`}
              className="inline-flex min-h-11 items-center gap-2 rounded-lg transition-colors hover:text-brand focus-visible:text-brand"
            >
              {name}
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
                className="shrink-0"
              >
                <path
                  d="m9 5 7 7-7 7"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </Link>
          ) : (
            name
          )}
        </h2>
        {(expanded || canExpand) && (
          <Button
            size="icon"
            className="shrink-0 border-0 bg-transparent"
            aria-label={`${name} 곡 ${expanded ? '접기' : '모두 펼치기'}`}
            aria-expanded={expanded}
            aria-controls={contentId}
            onClick={() => {
              setPosition({ size: pageSize, page: 0 })
              setExpanded((value) => !value)
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
      <div
        id={contentId}
        className="min-w-0"
        aria-busy={expanded && query.isFetching}
      >
        <CatalogExpansion>
          {query.isPending ? (
            <CompactSongSkeleton count={6} className={rowGrid} />
          ) : expanded && songs.length > 0 ? (
            <>
              <CompactSongGrid songs={songs} className={rowGrid} />
              {query.isFetching && (
                <p role="status" className="sr-only">
                  섹션의 곡을 모두 불러오는 중…
                </p>
              )}
            </>
          ) : songs.length > 0 ? (
            <HomeCarousel
              scrollable
              key={`${pageSize}-${retryVersion}`}
              label={name}
              pages={Array.from(
                { length: Math.ceil(songs.length / pageSize) },
                (_, index) => (
                  <CompactSongGrid
                    key={index}
                    songs={songs.slice(
                      index * pageSize,
                      (index + 1) * pageSize,
                    )}
                    className={rowGrid}
                  />
                ),
              )}
              hasMore={Boolean(query.hasNextPage) && !query.isError}
              loadingMore={
                query.isFetching && songs.length < (currentPage + 2) * pageSize
              }
              lastPageFull={songs.length % pageSize === 0}
              onBeforeNext={prepareNextPage}
              onPageChange={(page) => setPosition({ size: pageSize, page })}
            />
          ) : !query.isError ? (
            <StatePanel title="현재 공개된 곡이 없습니다" onRetry={onRefresh} />
          ) : null}
        </CatalogExpansion>
      </div>
      {query.isError && (
        <StatePanel
          title={
            missing
              ? '분류 정보를 다시 확인해 주세요'
              : '이 섹션의 곡을 불러오지 못했습니다'
          }
          description={
            changed
              ? '조회 조건 또는 조회수 집계가 바뀌었습니다. 다시 시도하면 이 섹션의 첫 페이지부터 불러옵니다.'
              : missing
                ? '그룹의 공개 곡 구성이 변경되었습니다. 다시 조회해 주세요.'
                : '다른 섹션은 계속 볼 수 있습니다.'
          }
          onRetry={retry}
        />
      )}
    </section>
  )
}

export function GroupedCatalog({
  groupBy,
  sort,
}: {
  groupBy: CatalogGrouping
  sort: CatalogSort
}) {
  const client = useQueryClient()
  const options = catalogGroupsOptions(groupBy, sort)
  const query = useInfiniteQuery(options)
  const [epoch, setEpoch] = useState(0)
  const groups = [
    ...new Map(
      query.data?.pages
        .flatMap((page) => page.items)
        .map((group) => [group.id, group]) ?? [],
    ).values(),
  ]
  const changed =
    query.error instanceof ApiError &&
    (query.error.code === 'VIEW_PUBLICATION_CHANGED' ||
      query.error.code === 'INVALID_CATALOG_QUERY')
  async function refresh() {
    client.removeQueries({ queryKey: groupSongsKey(groupBy, sort) })
    await client.resetQueries({ queryKey: options.queryKey, exact: true })
    setEpoch((value) => value + 1)
  }
  const retry = () => {
    if (changed) {
      void refresh()
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  return (
    <>
      {query.isPending ? (
        <CompactSongSkeleton count={6} className={rowGrid} />
      ) : !query.data && query.isError ? (
        <StatePanel title="분류 목록을 불러오지 못했습니다" onRetry={retry} />
      ) : groups.length === 0 ? (
        <StatePanel title="아직 공개된 노래가 없습니다" />
      ) : (
        groups.map((group) => (
          <GroupSection
            key={`${epoch}-${group.id}`}
            group={group}
            groupBy={groupBy}
            sort={sort}
            onRefresh={() => void refresh()}
          />
        ))
      )}
      {query.data && query.isError && (
        <StatePanel
          title="분류 목록을 더 불러오지 못했습니다"
          description={
            changed
              ? '조회수 집계 또는 조건이 바뀌었습니다. 다시 조회하면 처음부터 불러옵니다.'
              : '이미 불러온 섹션은 유지됩니다.'
          }
          onRetry={retry}
        />
      )}
      <CatalogLoadMore
        hasMore={Boolean(query.hasNextPage)}
        loading={query.isFetching}
        error={query.isError}
        onLoad={() => void query.fetchNextPage()}
      />
    </>
  )
}
