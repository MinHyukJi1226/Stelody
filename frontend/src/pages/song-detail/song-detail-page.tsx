import { useQuery } from '@tanstack/react-query'
import { Navigate, useLocation, useParams } from 'react-router'
import { z } from 'zod'
import {
  songDetailOptions,
  type SongDetail,
} from '@/features/songs/detail-queries'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { CompactSongGrid } from '@/features/songs/compact-song-grid'
import { ApiError } from '@/shared/api/errors'
import { StatePanel } from '@/shared/ui/state-panel'
import { PageState } from '@/shared/ui/page-state'
import { SongHeading } from './components/song-heading'
import { SongParticipants } from './components/song-participants'
import { formatDate } from './format'
import { SongVideo } from './components/song-video'
import { KaraokeNumbers, WorkInformation } from './components/song-resources'
import { SearchHelp } from './components/search-help'
import { SongViews } from './components/song-views'
import { PlaylistButton } from '@/features/playlists/playlist-button'
import { FavoriteButton } from '@/features/favorites/favorite-button'
import { isYoutubeId, songPath } from '@/features/songs/song-path'

export function SongDetailPage() {
  const { songId = '' } = useParams()
  const location = useLocation()
  const valid = z.uuid().safeParse(songId).success || isYoutubeId(songId)
  const query = useQuery({ ...songDetailOptions(songId), enabled: valid })
  const unavailable =
    !valid ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  if (
    !unavailable &&
    query.data?.song.youtubeId &&
    isYoutubeId(query.data.song.youtubeId) &&
    songId !== query.data.song.youtubeId
  ) {
    return (
      <Navigate
        replace
        to={`${songPath(query.data.song)}${location.search}${location.hash}`}
      />
    )
  }
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      {unavailable || !query.data ? (
        <div
          className={
            query.isPending && !unavailable
              ? 'flex flex-col gap-6 px-5 py-8 md:px-8'
              : 'flex min-w-0 flex-1 items-center px-5 py-8 md:px-8'
          }
        >
          {unavailable ? (
            <PageState
              heading="h1"
              kind="unavailable"
              title="노래를 찾을 수 없습니다"
              description="존재하지 않거나 공개되지 않은 노래입니다"
              action={{ to: '/songs', label: '전체곡 보기' }}
              showHome={false}
            />
          ) : query.isPending ? (
            <div
              role="status"
              aria-label="노래 상세 불러오는 중"
              className="flex flex-col gap-8"
              aria-busy="true"
            >
              <div className="h-10 w-24 rounded-xl bg-line" />
              <div className="h-12 w-2/3 rounded-xl bg-line" />
              <div className="h-14 w-1/3 rounded-xl bg-line" />
              <div className="aspect-video rounded-2xl bg-line" />
            </div>
          ) : (
            <PageState
              heading="h1"
              kind="error"
              title="노래 정보를 불러오지 못했습니다"
              description="잠시 후 다시 시도해 주세요."
              onRetry={() => void query.refetch()}
              action={{ to: '/songs', label: '전체곡 보기' }}
              showHome={false}
            />
          )}
        </div>
      ) : (
        <>
          {query.isError && (
            <div className="px-5 py-4 md:px-8">
              <StatePanel
                title="노래 정보를 갱신하지 못했습니다"
                description="이전에 불러온 정보를 표시하고 있습니다."
                onRetry={() => void query.refetch()}
              />
            </div>
          )}
          <SongContent
            key={`${query.data.song.id}-${query.data.representativeVideo.id}`}
            detail={query.data}
            reload={() => void query.refetch()}
          />
        </>
      )}
      <footer className="mt-auto grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-8">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}

function SongContent({
  detail,
  reload,
}: {
  detail: SongDetail
  reload: () => void
}) {
  const { song } = detail
  const members = useMemberDirectory(
    song.participants.some((participant) => participant.kind === 'MEMBER'),
  )
  return (
    <>
      <SongHeading
        detail={detail}
        members={members}
        actions={
          <div className="flex max-w-full shrink-0 items-start gap-2">
            <FavoriteButton songId={song.id} songTitle={song.title} iconOnly />
            <PlaylistButton songId={song.id} iconOnly />
          </div>
        }
      />
      <div className="flex min-w-0 flex-col gap-8 px-5 py-5 md:px-8">
        <div className="home-section-enter grid min-w-0 items-start gap-5 md:grid-cols-[minmax(0,3fr)_minmax(0,1fr)] md:gap-8">
          <SongVideo detail={detail} />
          <aside className="flex min-w-0 flex-col gap-5">
            <SongParticipants song={song} members={members} />
            <section aria-labelledby="published-title" className="grid gap-2">
              <h2 id="published-title" className="text-lg font-bold">
                공개일
              </h2>
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                <time className="text-sm" dateTime={song.publishedAt}>
                  {formatDate(song.publishedAt)}
                </time>
                {song.isSpecialEvent && (
                  <span className="ml-auto text-right text-xs text-brand wrap-anywhere">
                    {song.specialEventLabel || '특별한 날'}
                  </span>
                )}
              </div>
            </section>
            {song.type === 'ORIGINAL' && (
              <KaraokeNumbers entries={detail.karaoke} />
            )}
            <WorkInformation detail={detail} />
          </aside>
        </div>
        {detail.relatedSongs.length > 0 && (
          <section
            aria-labelledby="related-title"
            className="home-section-enter flex min-w-0 flex-col gap-3"
          >
            <h2 id="related-title" className="text-lg font-bold leading-[1.5]">
              같은 원곡의 다른 커버
            </h2>
            <CompactSongGrid songs={detail.relatedSongs} />
          </section>
        )}
        <SongViews
          songId={song.id}
          videoId={detail.representativeVideo.id}
          viewCount={song.viewCount}
          onVideoChanged={reload}
        />
        {(song.type === 'COVER' ||
          detail.searchHelp.visibility === 'DIFFICULT') && (
          <SearchHelp detail={detail} />
        )}
      </div>
    </>
  )
}
