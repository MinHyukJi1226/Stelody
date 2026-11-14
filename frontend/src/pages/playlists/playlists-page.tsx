import { Link } from 'react-router'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import type { Playlist } from '@/features/playlists/api'
import {
  isUncertainPlaylistWrite,
  playlistErrorMessage,
  playlistKeys,
  playlistListOptions,
} from '@/features/playlists/queries'
import { PlaylistCover } from '@/features/playlists/playlist-cover'
import { usePlaylistManagement } from '@/features/playlists/use-playlist-management'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { PageState } from '@/shared/ui/page-state'
import { StatePanel } from '@/shared/ui/state-panel'
import { useToast } from '@/shared/ui/toast-context'
import { LibraryDeleteButton } from '@/pages/favorites/components/library-delete-button'
import { LibraryPage } from '@/pages/favorites/components/library-page'

export function PlaylistsPage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const user = session.data?.id ?? ''
  const client = useQueryClient()
  const notify = useToast()
  const action = usePlaylistManagement(user, '/library/playlists')
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
      : null

  async function remove(playlist: Playlist) {
    try {
      const result = await action.write({ kind: 'delete', playlist })
      if (result?.kind === 'deleted') notify('플레이리스트를 삭제했어요.')
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return
      notify(playlistErrorMessage(error), 'error')
      if (
        isUncertainPlaylistWrite(error) ||
        (error instanceof ApiError && [404, 409].includes(error.status))
      ) {
        await query.refetch()
      }
    }
  }

  return (
    <LibraryPage
      playlists
      action={
        <Button asChild className="min-h-10 bg-transparent px-2 py-2">
          <Link to="/library/playlists/new">새로 만들기</Link>
        </Button>
      }
    >
      {query.isPending ? (
        <div
          role="status"
          aria-label="플레이리스트 불러오는 중"
          aria-busy="true"
          className="grid gap-8"
        >
          {[0, 1, 2].map((i) => (
            <div key={i} aria-hidden="true" className="flex items-center gap-4">
              <div className="size-20 shrink-0 rounded-xl bg-line md:size-[150px]" />
              <div className="grid min-w-0 flex-1 gap-2">
                <div className="h-4 w-28 max-w-full rounded bg-line" />
                <div className="h-3 w-14 max-w-full rounded bg-line" />
              </div>
              <div className="size-9 shrink-0 rounded-xl bg-line" />
              <div className="size-9 shrink-0 rounded-xl bg-line" />
            </div>
          ))}
        </div>
      ) : state ? (
        <PageState
          {...state}
          detail="나만 볼 수 있는 목록입니다."
          action={{ to: '/library', label: '즐겨찾기' }}
          {...(state.kind === 'error' ? { onRetry: () => void retry() } : {})}
        />
      ) : !items.length ? (
        <h2 className="home-section-enter text-lg font-bold leading-[1.5]">
          아직 만든 플레이리스트가 없습니다
        </h2>
      ) : (
        <>
          <div className="home-section-enter grid gap-6 md:gap-8">
            {items.map((playlist) => (
              <article
                key={playlist.id}
                className="flex min-w-0 items-center justify-between gap-3 md:gap-4"
              >
                <Link
                  to={`/library/playlists/${playlist.id}`}
                  aria-label={`${playlist.name} 목록 열기`}
                  className="flex min-w-0 flex-1 items-center gap-3 rounded-xl md:gap-5"
                >
                  <PlaylistCover
                    playlist={playlist}
                    userId={user}
                    className="size-20 shrink-0 rounded-xl md:size-[150px]"
                  />
                  <div className="grid min-w-0 gap-1">
                    <h2 className="break-words text-sm font-medium md:text-base">
                      {playlist.name}
                    </h2>
                    <p className="text-xs md:text-sm">
                      총 {playlist.totalCount.toLocaleString('ko-KR')}곡
                    </p>
                  </div>
                </Link>
                <div className="flex shrink-0 flex-col items-end gap-2 sm:flex-row sm:items-center md:gap-8">
                  <Button
                    asChild
                    size="icon"
                    className="size-9 rounded-xl border-0 bg-[#00e600] text-white"
                  >
                    <Link
                      to={`/library/playlists/${playlist.id}/edit?from=library`}
                      aria-label={`${playlist.name} 이름 수정`}
                    >
                      <svg
                        width="20"
                        height="20"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                      >
                        <path d="m15 5 4 4M4 20l4.7-1 11-11a2.8 2.8 0 0 0-4-4l-11 11L4 20Z" />
                      </svg>
                    </Link>
                  </Button>
                  <LibraryDeleteButton
                    key={playlist.version}
                    label={`${playlist.name} 삭제`}
                    pending={action.pending || !user || session.isError}
                    onConfirm={() => void remove(playlist)}
                  />
                </div>
              </article>
            ))}
          </div>
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
    </LibraryPage>
  )
}
