import { useQuery } from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import { HomeNews } from './components/home-news'
import { HomeLibrary } from './components/home-library'
import { LatestSongs } from './components/latest-songs'
import { TrendingPanel } from './components/trending-panel'
import { Recommendations } from './components/recommendations'

export function HomePage() {
  const { data: user } = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
  })

  return (
    <>
      <h1 className="sr-only">Stelody 메인</h1>
      <HomeNews />
      <div className="flex min-w-0 flex-col gap-8 pt-6 pb-10 md:gap-10">
        <TrendingPanel />
        {user && <HomeLibrary key={user.id} userId={user.id} />}
        <LatestSongs />
        <Recommendations />
      </div>
      <footer className="grid gap-1 pt-5 pb-6 text-xs text-muted">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </>
  )
}
