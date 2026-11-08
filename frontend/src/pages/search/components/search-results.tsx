import { useEffect, useRef } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import type { SearchCriteria } from '@/features/songs/search-params'
import { songSearchOptions } from '@/features/songs/search-queries'
import { uniqueSongs } from '@/features/songs/queries'
import { SearchSongGrid, SearchSongSkeleton } from './search-song-grid'
import { PageState } from '@/shared/ui/page-state'
import { loginPath } from '@/features/auth/return-path'
import { searchParams } from '@/features/songs/search-params'
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

export function SearchResults({ criteria }: { criteria: SearchCriteria }) {
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
  const denied = query.error instanceof ApiError && query.error.status === 403
  return (
    <section
      aria-label="검색 결과"
      className={`flex min-w-0 flex-col gap-4${query.isPending ? '' : ' home-section-enter'}`}
    >
      {query.isPending ? (
        <div
          aria-hidden="true"
          className="h-[27px] w-full max-w-[340px] rounded-lg bg-line"
        />
      ) : query.data && songs.length > 0 ? (
        <h1
          id="search-results-title"
          className="text-lg font-bold leading-[1.5] md:text-2xl"
          aria-live="polite"
        >
          {criteria.q ? `'${criteria.q}' 검색결과` : '검색결과'}
          {query.data &&
            ` ${query.data.pages[0]!.totalCount.toLocaleString('ko-KR')}건`}
        </h1>
      ) : null}
      {query.isPending ? (
        <SearchSongSkeleton />
      ) : !query.data && query.isError ? (
        <div className="flex min-h-[480px] items-center md:min-h-[600px]">
          <PageState
            kind={denied ? 'unavailable' : 'error'}
            title={
              denied
                ? '이 화면에 접근할 수 없습니다'
                : '검색 결과를 불러오지 못했습니다'
            }
            description={
              denied
                ? '본인의 계정과 접근 권한을 확인해 주세요.'
                : '잠시 후 다시 시도해 주세요.'
            }
            detail={`검색어 '${criteria.q}'는 유지됩니다.`}
            {...(!denied ? { onRetry: retry } : {})}
            action={{
              to: loginPath(`/search?${searchParams(criteria)}`),
              label: '로그인',
            }}
          />
        </div>
      ) : songs.length === 0 ? (
        <div role="status" className="flex flex-col gap-2">
          <h1 className="text-lg font-bold leading-[1.5]">
            검색결과가 없습니다.
          </h1>
          <p className="text-sm leading-[1.5] text-muted">
            다른 검색어로 다시 찾아보세요
          </p>
        </div>
      ) : (
        <SearchSongGrid songs={songs} />
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
        {query.isFetchingNextPage && <SearchSongSkeleton />}
        {query.hasNextPage && !query.isError && (
          <Button
            disabled={query.isFetching}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? '노래 더 불러오는 중…' : '노래 더 보기'}
          </Button>
        )}
        {!query.hasNextPage && songs.length > 0 && (
          <p role="status" className="sr-only">
            모든 검색 결과를 불러왔습니다.
          </p>
        )}
      </div>
    </section>
  )
}
