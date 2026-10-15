import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { latestSongsOptions } from '@/features/songs/queries'
import { SongGrid, SongGridSkeleton } from '@/features/songs/song-card'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { HomeSection } from './home-section'

export function LatestSongs() {
  const query = useQuery(latestSongsOptions)
  return (
    <HomeSection
      title="새로 공개된 노래"
      action={
        <Button asChild>
          <Link to="/songs" aria-label="최신곡 전체 보기">
            전체 보기
          </Link>
        </Button>
      }
    >
      {query.isPending ? (
        <SongGridSkeleton />
      ) : query.isError && !query.data ? (
        <StatePanel
          title="노래를 불러오지 못했습니다"
          description="잠시 후 다시 시도해 주세요."
          onRetry={() => void query.refetch()}
        />
      ) : (
        <>
          {query.data?.items.length ? (
            <SongGrid songs={query.data.items} />
          ) : (
            <StatePanel title="아직 공개된 노래가 없습니다" />
          )}
          {query.isError && query.data && (
            <StatePanel
              title="최신곡을 갱신하지 못했습니다"
              description="이전에 불러온 결과를 표시하고 있습니다."
              onRetry={() => void query.refetch()}
            />
          )}
        </>
      )}
    </HomeSection>
  )
}
