import { useQuery } from '@tanstack/react-query'
import { recommendationsOptions } from '@/features/songs/queries'
import { HomeSongSkeleton } from './home-song-grid'
import { HomeSongCarousel } from './home-song-carousel'
import { StatePanel } from '@/shared/ui/state-panel'
import { HomeSection } from './home-section'

export function Recommendations() {
  const query = useQuery(recommendationsOptions)
  return (
    <HomeSection title="오늘의 추천 곡" loading={query.isPending}>
      {query.isPending ? (
        <HomeSongSkeleton />
      ) : query.isError && !query.data ? (
        <StatePanel
          title="추천을 불러오지 못했습니다"
          onRetry={() => void query.refetch()}
        />
      ) : (
        <>
          {query.data?.items.length ? (
            <HomeSongCarousel songs={query.data.items} label="오늘의 추천 곡" />
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
