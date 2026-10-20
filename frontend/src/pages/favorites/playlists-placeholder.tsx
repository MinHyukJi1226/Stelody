import { LibraryTabs } from './components/library-tabs'
import { SiteFooter } from '@/shared/ui/site-footer'

export function PlaylistsPlaceholder() {
  return (
    <div className="favorites-page flex flex-col gap-12">
      <section className="flex flex-1 flex-col gap-8">
        <p className="text-xs text-brand">MY LIBRARY</p>
        <h1 className="text-[32px] font-bold leading-[1.5]">내 플레이리스트</h1>
        <LibraryTabs playlists />
        <p className="text-muted">플레이리스트 기능은 준비 중입니다.</p>
      </section>
      <SiteFooter />
    </div>
  )
}
