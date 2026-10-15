import { useQuery } from '@tanstack/react-query'
import { recommendationsOptions } from '@/features/songs/queries'
import { SongGrid, SongGridSkeleton } from '@/features/songs/song-card'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { HomeSection } from './home-section'

export function Recommendations() {
  const query = useQuery(recommendationsOptions)
  return (
    <HomeSection
      title="우연히 만나는 한 곡"
      action={
        <Button
          disabled={query.isFetching}
          aria-busy={query.isFetching}
          onClick={() => void query.refetch()}
        >
          {query.isFetching && !query.isPending ? '추천 중…' : '다시 추천'}
        </Button>
      }
    >
      {query.isPending ? (
        <SongGridSkeleton />
      ) : query.isError && !query.data ? (
        <StatePanel
          title="추천을 불러오지 못했습니다"
          onRetry={() => void query.refetch()}
        />
      ) : (
        <>
          {query.data?.items.length ? (
            <SongGrid songs={query.data.items} />
          ) : (
            <StatePanel title="추천할 노래가 아직 없습니다" />
          )}
          {query.isError && query.data && (
            <StatePanel
              title="새 추천을 불러오지 못했습니다"
              description="이전에 불러온 추천을 표시하고 있습니다."
              onRetry={() => void query.refetch()}
            />
          )}
        </>
      )}
    </HomeSection>
  )
}
