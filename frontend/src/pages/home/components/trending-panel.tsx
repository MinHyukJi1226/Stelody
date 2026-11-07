import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { participantLabel } from '@/features/songs/participant-label'
import { Artwork } from '@/shared/ui/artwork'
import { StatePanel } from '@/shared/ui/state-panel'
import { trendingOptions } from '../home-api'
import { HomeSection } from './home-section'
import { HomeScrollingText } from './home-scrolling-text'

const number = new Intl.NumberFormat('ko-KR')
const grid = 'grid grid-cols-2 gap-x-4 gap-y-3 md:gap-x-8 md:gap-y-5'

export function TrendingPanel() {
  const query = useQuery(trendingOptions)
  const items = query.data?.status === 'READY' ? query.data.items : []
  const members = useMemberDirectory(
    items.some(({ song }) => (song?.participants.length ?? 0) >= 2),
  )

  return (
    <HomeSection title="현재 인기 급상승 중인 곡" loading={query.isPending}>
      <p className="-mt-3 text-xs text-muted">최근 24시간 조회수 증가량</p>
      {query.isPending ? (
        <div role="status" aria-label="급상승 곡 불러오는 중" className={grid}>
          {Array.from({ length: 10 }, (_, index) => (
            <div
              key={index}
              aria-hidden="true"
              className="flex h-[72px] items-start gap-2 md:h-14"
            >
              <div className="aspect-video w-14 shrink-0 rounded-lg bg-line md:w-[100px]" />
              <div className="mt-1 grid w-full gap-2">
                <div className="h-4 w-3/4 rounded bg-line" />
                <div className="h-3 w-1/2 rounded bg-line" />
              </div>
            </div>
          ))}
        </div>
      ) : query.isError ? (
        <StatePanel
          title="급상승 곡을 불러오지 못했습니다"
          onRetry={() => void query.refetch()}
        />
      ) : query.data.status === 'DISABLED' ? (
        <p className="text-sm text-muted">준비 중입니다.</p>
      ) : query.data.status !== 'READY' ? (
        <p className="text-sm text-muted">급상승 집계를 기다리고 있습니다.</p>
      ) : items.length ? (
        <ol className={grid}>
          {items.flatMap(({ song, rank, increase }) =>
            song
              ? [
                  <li key={song.id} value={rank} className="min-w-0">
                    <Link
                      to={`/songs/${song.id}`}
                      className="grid min-h-[72px] min-w-0 content-start gap-2 rounded-lg md:min-h-14 md:grid-cols-[minmax(0,1fr)_auto] md:items-center md:gap-4"
                    >
                      <span className="flex min-w-0 items-center gap-2 md:gap-4">
                        <Artwork
                          src={song.thumbnailUrl}
                          className="aspect-video w-14 rounded-lg md:w-[100px]"
                          fallbackText="♪"
                        />
                        <span className="grid min-w-0 gap-1">
                          <HomeScrollingText className="text-sm">
                            {song.title}
                          </HomeScrollingText>
                          <HomeScrollingText className="text-xs text-muted">
                            {participantLabel(song, members)}
                          </HomeScrollingText>
                        </span>
                      </span>
                      <span className="justify-self-end text-xs whitespace-nowrap text-green">
                        +{number.format(increase)}회
                      </span>
                    </Link>
                  </li>,
                ]
              : [],
          )}
        </ol>
      ) : (
        <p className="text-sm text-muted">집계된 급상승 곡이 없습니다.</p>
      )}
    </HomeSection>
  )
}
