import { Link, useParams } from 'react-router'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { z } from 'zod'
import { sessionOptions } from '@/features/auth/session'
import {
  playlistDetailOptions,
  playlistItemsOptions,
  playlistKeys,
} from '@/features/playlists/queries'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { PageState } from '@/shared/ui/page-state'
import { StatePanel } from '@/shared/ui/state-panel'
import { SiteFooter } from '@/shared/ui/site-footer'
import { PlaylistLoading } from './components/playlist-loading'
import { PlaylistSong } from './components/playlist-song'

export function PlaylistDetailPage() {
  const { playlistId = '' } = useParams()
  const valid = z.uuid().safeParse(playlistId).success
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const user = session.data?.id ?? ''
  const client = useQueryClient()
  const enabled = valid && !!user && !session.isError
  const summary = useQuery({
    ...playlistDetailOptions(user, playlistId),
    enabled,
  })
  const query = useInfiniteQuery({
    ...playlistItemsOptions(user, playlistId),
    enabled,
  })
  const items = [
    ...new Map(
      query.data?.pages.flatMap((p) => p.items).map((p) => [p.id, p]) ?? [],
    ).values(),
  ]
  const members = useMemberDirectory(
    items.some((i) => i.song && i.song.participants.length >= 2),
  )
  const error = summary.error || query.error
  const denied =
    !valid ||
    [summary.error, query.error].some(
      (e) => e instanceof ApiError && [403, 404].includes(e.status),
    )
  const resetNeeded =
    query.error instanceof ApiError && [400, 409].includes(query.error.status)
  async function retry() {
    if (resetNeeded) {
      await Promise.all([
        client.resetQueries({
          queryKey: playlistKeys.detail(user, playlistId),
          exact: true,
        }),
        client.resetQueries({
          queryKey: playlistKeys.items(user, playlistId),
          exact: true,
        }),
      ])
    } else if (query.isFetchNextPageError) await query.fetchNextPage()
    else await Promise.all([summary.refetch(), query.refetch()])
  }
  const empty = !!query.data && !items.length
  const state = denied
    ? {
        kind: 'unavailable' as const,
        title: '이 목록에 접근할 수 없습니다',
        description: '목록이 없거나 현재 계정에서 볼 수 없습니다.',
      }
    : error && (!summary.data || !items.length)
      ? {
          kind: 'error' as const,
          title: '불러오지 못했어요',
          description: '연결 상태를 확인한 뒤 다시 시도해 주세요.',
        }
      : empty
        ? {
            kind: 'empty' as const,
            title: '아직 담은 곡이 없습니다',
            description: '노래를 찾아 이 플레이리스트에 담아보세요.',
          }
        : null
  const loading = !denied && !state && (summary.isPending || query.isPending)
  const counts = query.data?.pages[0]
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section
        className={`flex min-w-0 flex-1 flex-col gap-8 ${state ? 'justify-center' : ''}`}
      >
        {state ? (
          <>
            <h1 className="sr-only">
              {!denied && summary.data ? summary.data.name : '플레이리스트'}
            </h1>
            <PageState
              {...state}
              detail="나만 볼 수 있는 목록입니다."
              action={{
                to: empty ? '/search' : '/library/playlists',
                label: empty ? '곡 찾기' : '내 플레이리스트',
              }}
              {...(state.kind === 'error'
                ? { onRetry: () => void retry() }
                : {})}
            />
          </>
        ) : loading ? (
          <>
            <h1 className="sr-only">플레이리스트</h1>
            <PlaylistLoading />
          </>
        ) : (
          <>
            <p className="text-xs leading-[1.5] text-brand">PRIVATE PLAYLIST</p>
            <h1 className="break-words text-[32px] font-bold leading-[1.5]">
              {summary.data?.name}
            </h1>
            <div className="flex flex-col gap-1 text-muted">
              <p>총 {counts?.totalCount ?? 0}곡</p>
              {counts && counts.totalCount > counts.availableCount && (
                <p>
                  이용 불가 곡 {counts.totalCount - counts.availableCount}곡
                </p>
              )}
            </div>
            <div>
              <Button asChild variant="primary">
                <Link to="/search">＋ 곡 추가하기</Link>
              </Button>
            </div>
            {items.map((item) =>
              item.available && item.song ? (
                <PlaylistSong
                  key={item.id}
                  song={item.song}
                  members={members}
                />
              ) : (
                <article key={item.id} className="flex flex-col gap-2 p-4">
                  <h2 className="font-medium">현재 이용할 수 없는 곡</h2>
                  <p className="text-xs leading-[1.5] text-muted">
                    {item.unavailableMessage || '저장한 순서는 유지됩니다.'}
                  </p>
                </article>
              ),
            )}
            {error && (
              <StatePanel
                title={
                  resetNeeded
                    ? '목록이 변경되었습니다'
                    : '목록을 갱신하지 못했어요'
                }
                description={
                  resetNeeded
                    ? '처음부터 다시 불러와 최신 목록을 확인해 주세요.'
                    : '이미 불러온 곡은 유지됩니다.'
                }
                onRetry={() => void retry()}
              />
            )}
            {query.hasNextPage && !query.isError && (
              <div>
                <Button
                  disabled={query.isFetching}
                  onClick={() => void query.fetchNextPage()}
                >
                  {query.isFetching ? '불러오는 중…' : '곡 더 보기'}
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
