import type { ReactNode } from 'react'
import { LibraryTabs } from './library-tabs'

export function LibraryPage({
  playlists = false,
  action,
  children,
}: {
  playlists?: boolean
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <section className="flex min-w-0 flex-1 flex-col gap-8 px-5 py-8 md:px-8">
        <div className="home-section-enter flex flex-col gap-3">
          <h1 className="text-2xl font-bold leading-[1.5]">내 보관함</h1>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <LibraryTabs playlists={playlists} />
            {action}
          </div>
        </div>
        {children}
      </section>
      <footer className="grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
