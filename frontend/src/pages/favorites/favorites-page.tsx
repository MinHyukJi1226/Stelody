import { useEffect, useRef } from 'react'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import { favoriteListOptions } from '@/features/favorites/queries'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { SiteFooter } from '@/shared/ui/site-footer'
import { StatePanel } from '@/shared/ui/state-panel'
import { PageState } from '@/shared/ui/page-state'
import { FavoriteItem } from './components/favorite-item'
import { FavoriteSkeleton } from './components/favorite-skeleton'
import { LibraryTabs } from './components/library-tabs'

export function FavoritesPage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const client = useQueryClient()
  const userId = session.data?.id ?? ''
  const options = favoriteListOptions(userId)
  const query = useInfiniteQuery({
    ...options,
    enabled: !!userId && !session.isError,
  })
  const items = [
    ...new Map(
      query.data?.pages
        .flatMap((p) => p.items)
        .map((item) => [item.songId, item]) ?? [],
    ).values(),
  ]
  const members = useMemberDirectory(
    items.some((item) => item.song && item.song.participants.length >= 2),
  )
  const summary = query.data?.pages[0]
  const unavailable =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  const invalidCursor =
    query.error instanceof ApiError &&
    query.error.code === 'INVALID_FAVORITE_QUERY'
  const retry = () => {
    if (invalidCursor) {
      void client.resetQueries({ queryKey: options.queryKey, exact: true })
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  const sentinel = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  useEffect(() => {
    if (
      !sentinel.current ||
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
    observer.observe(sentinel.current)
    return () => observer.disconnect()
  }, [hasNextPage, isFetching, isError, fetchNextPage])

  const empty = !!query.data && !items.length
  const state = unavailable
    ? {
        kind: 'unavailable' as const,
        title: '이 화면에 접근할 수 없습니다',
        description: '즐겨찾기를 볼 수 없습니다. 계정 상태를 확인해 주세요.',
      }
    : query.isError && !items.length
      ? {
          kind: 'error' as const,
          title: '불러오지 못했어요',
          description: '연결 상태를 확인한 뒤 다시 시도해 주세요.',
        }
      : empty
        ? {
            kind: 'empty' as const,
            title: '아직 저장한 노래가 없습니다',
            description: '마음에 드는 곡에서 즐겨찾기를 눌러보세요.',
          }
        : null

  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section
        className={`flex min-w-0 flex-1 flex-col gap-8 ${state ? 'justify-center' : ''}`}
      >
        {query.isPending ? (
          <FavoriteSkeleton />
        ) : state ? (
          <>
            <h1 className="sr-only">내 즐겨찾기</h1>
            <PageState
              {...state}
              detail={
                empty
                  ? '저장 0곡 · 나만 보기'
                  : '나만 볼 수 있는 즐겨찾기 목록입니다.'
              }
              action={{ to: '/search', label: '노래 찾기' }}
              {...(state.kind === 'error' ? { onRetry: retry } : {})}
            />
          </>
        ) : (
          <>
            <p className="text-xs leading-[1.5] text-brand">MY LIBRARY</p>
            <h1 className="text-[32px] font-bold leading-[1.5]">내 즐겨찾기</h1>
            <p className="text-muted">나만 볼 수 있는 고정 목록</p>
            <LibraryTabs />
            <div className="flex flex-wrap items-start gap-2">
              <p className="min-w-0 flex-1 text-lg font-bold leading-[1.5]">
                저장 {summary?.totalCount ?? 0}곡 · 이용 가능{' '}
                {summary?.availableCount ?? 0}곡
              </p>
              <span
                className="inline-flex min-h-[53px] items-center rounded-xl bg-surface p-4 text-sm"
                aria-label="정렬: 최근 저장순"
              >
                최근 저장순
              </span>
            </div>
            <div className="grid grid-cols-1 items-start gap-6 md:grid-cols-2 lg:grid-cols-3">
              {items.map((item) => (
                <FavoriteItem key={item.songId} item={item} members={members} />
              ))}
            </div>
            {query.isError && query.data && (
              <StatePanel
                title={
                  query.isFetchNextPageError
                    ? '즐겨찾기를 더 불러오지 못했습니다'
                    : '즐겨찾기를 갱신하지 못했습니다'
                }
                description={
                  invalidCursor
                    ? '목록을 처음부터 다시 불러와 주세요.'
                    : '이미 불러온 목록은 유지됩니다.'
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
                  {query.isFetchingNextPage
                    ? '즐겨찾기 더 불러오는 중…'
                    : '즐겨찾기 더 보기'}
                </Button>
              )}
              {!query.hasNextPage && items.length > 0 && (
                <p role="status" className="text-sm text-muted">
                  모든 즐겨찾기를 불러왔습니다.
                </p>
              )}
            </div>
          </>
        )}
      </section>
      <SiteFooter />
    </div>
  )
}
