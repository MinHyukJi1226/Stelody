import type { ReactNode } from 'react'

export function PlaylistFormLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col px-5 md:px-8">
      <section className="home-section-enter flex w-full flex-1 flex-col gap-7 py-8 md:w-[88%]">
        {children}
      </section>
    </div>
  )
}
