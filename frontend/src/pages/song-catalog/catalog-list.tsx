import { useEffect, useRef } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { uniqueSongs } from '@/features/songs/queries'
import {
  CompactSongGrid,
  CompactSongSkeleton,
} from '@/features/songs/compact-song-grid'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { ApiError } from '@/shared/api/errors'
import { catalogOptions, type CatalogSort } from './catalog-api'

export function CatalogLoadMore({
  hasMore,
  loading,
  error,
  onLoad,
}: {
  hasMore: boolean
  loading: boolean
  error: boolean
  onLoad: () => void
}) {
  const sentinel = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = sentinel.current
    if (
      !element ||
      !hasMore ||
      loading ||
      error ||
      !('IntersectionObserver' in window)
    )
      return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) onLoad()
      },
      { rootMargin: '200px' },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [hasMore, loading, error, onLoad])
  return (
    <div ref={sentinel}>
      {hasMore && !error && (
        <Button disabled={loading} onClick={onLoad}>
          {loading ? '노래 더 불러오는 중…' : '노래 더 보기'}
        </Button>
      )}
    </div>
  )
}
export function FlatCatalog({ sort }: { sort: CatalogSort }) {
  const options = catalogOptions(sort)
  const query = useInfiniteQuery(options)
  const client = useQueryClient()
  const songs = uniqueSongs(query.data?.pages ?? [])
  const changed =
    query.error instanceof ApiError &&
    query.error.code === 'VIEW_PUBLICATION_CHANGED'
  const retry = () => {
    if (changed) {
      void client.resetQueries({ queryKey: options.queryKey, exact: true })
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  return (
    <section
      aria-label="전체곡 목록"
      className={`flex min-w-0 flex-col gap-6${query.isPending ? '' : ' home-section-enter'}`}
    >
      {query.isPending ? (
        <CompactSongSkeleton
          count={12}
          className="grid-cols-2 gap-x-4 md:grid-cols-3 lg:grid-cols-6 md:gap-x-6"
        />
      ) : !query.data && query.isError ? (
        <StatePanel title="노래를 불러오지 못했습니다" onRetry={retry} />
      ) : songs.length === 0 ? (
        <StatePanel title="아직 공개된 노래가 없습니다" />
      ) : (
        <CompactSongGrid
          songs={songs}
          className="grid-cols-2 gap-x-4 md:grid-cols-3 lg:grid-cols-6 md:gap-x-6"
        />
      )}
      {query.isError && query.data && (
        <StatePanel
          title="노래를 더 불러오지 못했습니다"
          description={
            changed
              ? '조회수 집계가 갱신되었습니다. 다시 시도하면 첫 페이지부터 불러옵니다.'
              : '이미 불러온 목록은 유지됩니다.'
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
    </section>
  )
}
