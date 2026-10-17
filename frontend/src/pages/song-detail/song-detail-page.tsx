import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { z } from 'zod'
import {
  songDetailOptions,
  type SongDetail,
} from '@/features/songs/detail-queries'
import { SongGrid } from '@/features/songs/song-card'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { SiteFooter } from '@/shared/ui/site-footer'
import { StatePanel } from '@/shared/ui/state-panel'
import { SongHeading } from './components/song-heading'
import { SongVideo } from './components/song-video'
import { ExternalLinks } from './components/external-links'
import { KaraokeNumbers, WorkInformation } from './components/song-resources'
import { SearchHelp } from './components/search-help'
import { SongViews } from './components/song-views'

export function SongDetailPage() {
  const { songId = '' } = useParams()
  const valid = z.uuid().safeParse(songId).success
  const query = useQuery({ ...songDetailOptions(songId), enabled: valid })
  const unavailable =
    !valid ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  return (
    <div className="flex min-w-0 flex-col gap-8">
      {unavailable ? (
        <>
          <h1 className="text-2xl font-bold">노래를 찾을 수 없습니다</h1>
          <StatePanel title="존재하지 않거나 공개되지 않은 노래입니다" />
          <Button asChild>
            <Link to="/songs">전체곡 보기</Link>
          </Button>
        </>
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
      ) : !query.data ? (
        <StatePanel
          title="노래 정보를 불러오지 못했습니다"
          description="잠시 후 다시 시도해 주세요."
          onRetry={() => void query.refetch()}
        />
      ) : (
        <>
          {query.isError && (
            <StatePanel
              title="노래 정보를 갱신하지 못했습니다"
              description="이전에 불러온 정보를 표시하고 있습니다."
              onRetry={() => void query.refetch()}
            />
          )}
          <SongContent
            key={`${query.data.song.id}-${query.data.representativeVideo.id}`}
            detail={query.data}
            reload={() => void query.refetch()}
          />
        </>
      )}
      <SiteFooter />
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
  return (
    <>
      <SongHeading detail={detail} />
      <SongVideo detail={detail} />
      <KaraokeNumbers entries={detail.karaoke} />
      {detail.links.length > 0 && <ExternalLinks links={detail.links} />}
      <div className="flex flex-col gap-2">
        <div
          className="flex flex-wrap gap-2 md:gap-3"
          aria-describedby="saving-status"
        >
          <Button
            disabled
            className="min-h-12 min-w-0 flex-1 border border-line md:w-44 md:flex-none"
          >
            ♡ 즐겨찾기
          </Button>
          <Button
            disabled
            className="min-h-12 min-w-0 flex-1 border border-line md:w-44 md:flex-none"
          >
            ＋ 플레이리스트
          </Button>
        </div>
        <p id="saving-status" className="text-xs text-muted">
          저장 기능은 준비 중입니다.
        </p>
      </div>
      {(detail.song.type === 'COVER' ||
        detail.searchHelp.visibility === 'DIFFICULT') && (
        <SearchHelp detail={detail} />
      )}
      <WorkInformation detail={detail} />
      <SongViews
        songId={detail.song.id}
        videoId={detail.representativeVideo.id}
        viewCount={detail.song.viewCount}
        observedAt={detail.song.viewsObservedAt}
        onVideoChanged={reload}
      />
      {detail.relatedSongs.length > 0 && (
        <section
          aria-labelledby="related-title"
          className="flex flex-col gap-8"
        >
          <h2 id="related-title" className="text-2xl font-bold leading-[1.5]">
            같은 원곡의 다른 커버
          </h2>
          <SongGrid songs={detail.relatedSongs} />
        </section>
      )}
    </>
  )
}
