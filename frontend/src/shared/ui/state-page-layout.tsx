import type { ReactNode } from 'react'

export function StatePageLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <div className="flex min-w-0 flex-1 items-center justify-center px-5 py-8 pb-16 md:px-8">
        {children}
      </div>
    </div>
  )
}
