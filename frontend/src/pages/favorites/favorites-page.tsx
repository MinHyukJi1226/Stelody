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
import { StatePanel } from '@/shared/ui/state-panel'
import { PageState } from '@/shared/ui/page-state'
import { FavoriteItem } from './components/favorite-item'
import { FavoriteSkeleton } from './components/favorite-skeleton'
import { LibraryPage } from './components/library-page'

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
      : null

  return (
    <LibraryPage>
      {query.isPending ? (
        <FavoriteSkeleton />
      ) : state ? (
        <PageState
          {...state}
          detail="나만 볼 수 있는 즐겨찾기 목록입니다."
          action={{ to: '/search', label: '노래 찾기' }}
          {...(state.kind === 'error' ? { onRetry: retry } : {})}
        />
      ) : empty ? (
        <h2 className="home-section-enter text-lg font-bold leading-[1.5]">
          아직 저장한 노래가 없습니다
        </h2>
      ) : (
        <>
          <div className="home-section-enter grid gap-6 md:gap-8">
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
          </div>
        </>
      )}
    </LibraryPage>
  )
}
