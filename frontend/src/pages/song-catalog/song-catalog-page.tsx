import { useEffect, useRef } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { songCatalogOptions, uniqueSongs } from '@/features/songs/queries'
import { SongGrid, SongGridSkeleton } from '@/features/songs/song-card'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'

export function SongCatalogPage() {
  const query = useInfiniteQuery(songCatalogOptions)
  const sentinel = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  useEffect(() => {
    const element = sentinel.current
    if (
      !element ||
      !hasNextPage ||
      isFetching ||
      isError ||
      !('IntersectionObserver' in window)
    )
      return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) void fetchNextPage()
      },
      { rootMargin: '200px' },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [hasNextPage, isFetching, isError, fetchNextPage])

  const songs = uniqueSongs(query.data?.pages ?? [])
  const retry = () =>
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  return (
    <section aria-labelledby="catalog-title" className="flex flex-col gap-8">
      <div>
        <h1 id="catalog-title" className="text-[32px] font-bold leading-[1.5]">
          전체곡
        </h1>
        <p className="mt-2 text-sm text-muted">
          공개일 최신순
          {query.data &&
            ` · 총 ${query.data.pages[0]!.totalCount.toLocaleString('ko-KR')}곡`}
        </p>
      </div>
      {query.isPending ? (
        <SongGridSkeleton count={8} />
      ) : !query.data && query.isError ? (
        <StatePanel title="노래를 불러오지 못했습니다" onRetry={retry} />
      ) : songs.length === 0 ? (
        <StatePanel title="아직 공개된 노래가 없습니다" />
      ) : (
        <SongGrid songs={songs} />
      )}
      {query.isError && query.data && (
        <StatePanel
          title="노래를 더 불러오지 못했습니다"
          description="이미 불러온 목록은 유지됩니다."
          onRetry={retry}
        />
      )}
      <div ref={sentinel}>
        {query.hasNextPage && !query.isError && (
          <Button
            disabled={query.isFetching}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? '노래 더 불러오는 중…' : '노래 더 보기'}
          </Button>
        )}
        {!query.hasNextPage && songs.length > 0 && (
          <p role="status" className="text-sm text-muted">
            모든 노래를 불러왔습니다.
          </p>
        )}
      </div>
    </section>
  )
}
