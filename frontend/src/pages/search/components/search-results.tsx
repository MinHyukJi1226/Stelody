import { useEffect, useRef } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import type { SearchCriteria } from '@/features/songs/search-params'
import { songSearchOptions } from '@/features/songs/search-queries'
import { uniqueSongs } from '@/features/songs/queries'
import { SongGrid, SongGridSkeleton } from '@/features/songs/song-card'
import { StatePanel } from '@/shared/ui/state-panel'
import { Button } from '@/shared/ui/button'
import { ApiError } from '@/shared/api/errors'

const fieldLabels: Record<string, string> = {
  startDate: '시작일',
  endDate: '종료일',
  year: '연도',
  q: '검색어',
}

const dateErrorMessages: Record<string, string> = {
  INVALID_DATE: '올바른 날짜를 입력해 주세요.',
  DATE_OUT_OF_RANGE: '1900년부터 2100년 사이의 날짜를 입력해 주세요.',
  INVALID_DATE_RANGE: '시작일은 종료일보다 늦을 수 없습니다.',
  CONFLICTING_FILTER: '연도와 날짜 범위를 함께 지정할 수 없습니다.',
}

export function SearchResults({
  criteria,
  onSort,
}: {
  criteria: SearchCriteria
  onSort: (sort: SearchCriteria['sort']) => void
}) {
  const queryClient = useQueryClient()
  const options = songSearchOptions(criteria)
  const query = useInfiniteQuery(options)
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
  const publicationChanged =
    query.error instanceof ApiError &&
    query.error.status === 409 &&
    query.error.code === 'VIEW_PUBLICATION_CHANGED'
  const retry = () => {
    if (publicationChanged) {
      void queryClient.resetQueries({ queryKey: options.queryKey, exact: true })
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  const fieldErrors =
    query.error instanceof ApiError ? query.error.fieldErrors : []
  return (
    <section
      aria-labelledby="search-results-title"
      className="flex min-w-0 flex-col gap-8"
    >
      <div className="flex min-w-0 items-center justify-between gap-2">
        <h2
          id="search-results-title"
          className="text-2xl font-bold leading-[1.5]"
          aria-live="polite"
        >
          검색 결과
          {query.data &&
            ` ${query.data.pages[0]!.totalCount.toLocaleString('ko-KR')}곡`}
        </h2>
        <div className="shrink-0">
          <label htmlFor="search-sort" className="sr-only">
            검색 결과 정렬
          </label>
          <select
            id="search-sort"
            value={criteria.sort}
            onChange={(e) => onSort(e.target.value as SearchCriteria['sort'])}
            className="min-h-[53px] rounded-xl bg-surface p-4 text-sm text-ink"
          >
            <option value="RELEVANCE">관련도순</option>
            <option value="LATEST">최신순</option>
            <option value="VIEWS">조회수순</option>
          </select>
        </div>
      </div>
      {query.isPending ? (
        <SongGridSkeleton />
      ) : !query.data && query.isError ? (
        <StatePanel
          title="검색 결과를 불러오지 못했습니다"
          description="잠시 후 다시 시도해 주세요."
          onRetry={retry}
        />
      ) : songs.length === 0 ? (
        <StatePanel
          title="찾은 노래가 없습니다"
          description="검색어를 바꾸거나 필터를 줄여보세요."
        />
      ) : (
        <SongGrid songs={songs} />
      )}
      {query.isError && fieldErrors.length > 0 && (
        <ul
          aria-label="검색 조건 오류"
          role="alert"
          className="rounded-xl border border-line bg-surface p-4 text-sm text-error"
        >
          {fieldErrors.map(({ field, code, message }) => (
            <li key={`${field}-${code}`}>
              {fieldLabels[field] ?? field}:{' '}
              {dateErrorMessages[code] ?? message}
            </li>
          ))}
        </ul>
      )}
      {query.isError && query.data && (
        <StatePanel
          title={
            query.isFetchNextPageError
              ? '검색 결과를 더 불러오지 못했습니다'
              : '검색 결과를 갱신하지 못했습니다'
          }
          description={
            publicationChanged
              ? '조회수 집계가 갱신되었습니다. 다시 시도하면 첫 페이지부터 불러옵니다.'
              : '이전에 불러온 결과를 표시하고 있습니다.'
          }
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
            모든 검색 결과를 불러왔습니다.
          </p>
        )}
      </div>
    </section>
  )
}
