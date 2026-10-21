import { Link } from 'react-router'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import { playlistKeys, playlistListOptions } from '@/features/playlists/queries'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { PageState } from '@/shared/ui/page-state'
import { SiteFooter } from '@/shared/ui/site-footer'
import { StatePanel } from '@/shared/ui/state-panel'
import { LibraryTabs } from '@/pages/favorites/components/library-tabs'
import { PlaylistLoading } from './components/playlist-loading'

export function PlaylistsPage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const user = session.data?.id ?? ''
  const client = useQueryClient()
  const query = useInfiniteQuery({
    ...playlistListOptions(user),
    enabled: !!user && !session.isError,
  })
  const items = [
    ...new Map(
      query.data?.pages.flatMap((p) => p.items).map((p) => [p.id, p]) ?? [],
    ).values(),
  ]
  const denied =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  const retry = () =>
    query.error instanceof ApiError && query.error.status === 400
      ? client.resetQueries({ queryKey: playlistKeys.list(user), exact: true })
      : query.isFetchNextPageError
        ? query.fetchNextPage()
        : query.refetch()
  const state = denied
    ? {
        kind: 'unavailable' as const,
        title: '이 목록에 접근할 수 없습니다',
        description: '계정 상태를 확인해 주세요.',
      }
    : query.isError && !items.length
      ? {
          kind: 'error' as const,
          title: '불러오지 못했어요',
          description: '연결 상태를 확인한 뒤 다시 시도해 주세요.',
        }
      : !query.isPending && !items.length
        ? {
            kind: 'empty' as const,
            title: '아직 만든 플레이리스트가 없습니다',
            description: '첫 플레이리스트에 좋아하는 노래를 모아보세요.',
          }
        : null
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      {(query.isPending || state) && <LibraryTabs playlists />}
      <section
        className={`flex min-w-0 flex-1 flex-col gap-8 ${state ? 'justify-center' : ''}`}
      >
        {query.isPending ? (
          <>
            <h1 className="sr-only">내 플레이리스트</h1>
            <PlaylistLoading />
          </>
        ) : state ? (
          <>
            <h1 className="sr-only">내 플레이리스트</h1>
            <PageState
              {...state}
              detail="나만 볼 수 있는 목록입니다."
              action={{
                to:
                  state.kind === 'empty'
                    ? '/library/playlists/new'
                    : '/library',
                label: state.kind === 'empty' ? '첫 목록 만들기' : '내 보관함',
              }}
              {...(state.kind === 'error'
                ? { onRetry: () => void retry() }
                : {})}
            />
          </>
        ) : (
          <>
            <p className="text-xs leading-[1.5] text-brand">MY PLAYLISTS</p>
            <h1 className="text-[32px] font-bold leading-[1.5]">
              내 취향대로 모아두기
            </h1>
            <p className="text-muted">모든 플레이리스트는 나만 볼 수 있어요.</p>
            <LibraryTabs playlists />
            <div>
              <Button asChild variant="primary">
                <Link to="/library/playlists/new">＋ 새 플레이리스트</Link>
              </Button>
            </div>
            {items.map((playlist) => (
              <article
                key={playlist.id}
                className="flex flex-col gap-4 rounded-2xl bg-surface p-4 md:flex-row md:gap-3"
              >
                <div className="flex min-w-0 flex-1 flex-col gap-4 md:gap-1">
                  <h2 className="break-words text-lg font-bold leading-[1.5]">
                    {playlist.name}
                  </h2>
                  <p className="text-sm leading-[1.5] text-muted">
                    {playlist.totalCount
                      ? `${playlist.totalCount}곡 · 이용 가능 ${playlist.availableCount}곡 · 나만 보기`
                      : '아직 담은 곡이 없습니다'}
                  </p>
                </div>
                <div className="flex flex-1 flex-wrap gap-2">
                  {playlist.totalCount === 0 && (
                    <Button asChild>
                      <Link to="/search">곡 찾기</Link>
                    </Button>
                  )}
                  <Button asChild>
                    <Link to={`/library/playlists/${playlist.id}`}>
                      목록 열기
                    </Link>
                  </Button>
                </div>
              </article>
            ))}
            {query.isError && (
              <StatePanel
                title="목록을 갱신하지 못했어요"
                onRetry={() => void retry()}
              />
            )}
            {query.hasNextPage && !query.isError && (
              <div>
                <Button
                  disabled={query.isFetching}
                  onClick={() => void query.fetchNextPage()}
                >
                  {query.isFetching ? '불러오는 중…' : '목록 더 보기'}
                </Button>
              </div>
            )}
          </>
        )}
      </section>
      <SiteFooter />
    </div>
  )
}
