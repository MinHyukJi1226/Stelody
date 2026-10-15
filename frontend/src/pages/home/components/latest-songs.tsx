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
      ) : query.isError ? (
        <StatePanel
          title="노래를 불러오지 못했습니다"
          description="잠시 후 다시 시도해 주세요."
          onRetry={() => void query.refetch()}
        />
      ) : query.data.items.length === 0 ? (
        <StatePanel title="아직 공개된 노래가 없습니다" />
      ) : (
        <SongGrid songs={query.data.items} />
      )}
    </HomeSection>
  )
}
