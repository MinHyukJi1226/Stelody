import type { ReactNode } from 'react'

export function PlaylistFormLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col px-5 md:px-8">
      <section className="home-section-enter flex w-full flex-1 flex-col gap-7 py-8 md:w-[88%]">
        {children}
      </section>
      <footer className="grid gap-1 px-2 pt-5 pb-6 text-xs text-muted">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
