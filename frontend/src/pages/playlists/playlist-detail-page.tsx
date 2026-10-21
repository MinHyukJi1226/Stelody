import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { z } from 'zod'
import { sessionOptions } from '@/features/auth/session'
import { playlistEditorOptions } from '@/features/playlists/queries'
import { ApiError } from '@/shared/api/errors'
import { PageState } from '@/shared/ui/page-state'
import { SiteFooter } from '@/shared/ui/site-footer'
import { PlaylistLoading } from './components/playlist-loading'
import { PlaylistEditor } from './components/playlist-editor'

export function PlaylistDetailPage() {
  const { playlistId = '' } = useParams()
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const user = session.data?.id ?? ''
  const valid = z.uuid().safeParse(playlistId).success
  const query = useQuery({
    ...playlistEditorOptions(user, playlistId),
    enabled: valid && !!user && !session.isError,
  })
  const denied =
    !valid ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  const conflict =
    query.error instanceof ApiError && query.error.code === 'PLAYLIST_CHANGED'
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section
        className={`flex min-w-0 flex-1 flex-col gap-8 ${denied || (!query.data && query.isError) ? 'justify-center' : ''}`}
      >
        {denied || (!query.data && query.isError) ? (
          <>
            <h1 className="sr-only">플레이리스트</h1>
            <PageState
              kind={denied ? 'unavailable' : 'error'}
              title={
                denied
                  ? '이 목록에 접근할 수 없습니다'
                  : conflict
                    ? '목록이 변경되었습니다'
                    : '불러오지 못했어요'
              }
              description={
                denied
                  ? '목록이 없거나 현재 계정에서 볼 수 없습니다.'
                  : conflict
                    ? '전체 목록을 다시 불러와 최신 내용을 확인해 주세요.'
                    : '연결 상태를 확인한 뒤 다시 시도해 주세요.'
              }
              action={{ to: '/library/playlists', label: '내 플레이리스트' }}
              {...(!denied ? { onRetry: () => void query.refetch() } : {})}
            />
          </>
        ) : query.isPending ? (
          <>
            <h1 className="sr-only">플레이리스트</h1>
            <PlaylistLoading />
          </>
        ) : (
          query.data && (
            <PlaylistEditor
              key={`${user}:${playlistId}`}
              userId={user}
              snapshot={query.data}
              fetching={query.isFetching}
              readError={query.error}
              onReload={async () => {
                const result = await query.refetch()
                if (result.error) throw result.error
                return result.data!
              }}
            />
          )
        )}
      </section>
      <SiteFooter />
    </div>
  )
}
