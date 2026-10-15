import { SearchHero } from './components/search-hero'
import { LatestSongs } from './components/latest-songs'
import { TrendingPanel } from './components/trending-panel'
import { Recommendations } from './components/recommendations'
import { HomeMembers } from './components/home-members'
import { HomeFooter } from './components/home-footer'

export function HomePage() {
  return (
    <div className="home-page flex min-w-0 flex-col gap-8">
      <SearchHero />
      <LatestSongs />
      <TrendingPanel />
      <Recommendations />
      <HomeMembers />
      <HomeFooter />
    </div>
  )
}
