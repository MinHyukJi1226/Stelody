import type { ReactNode } from 'react'

export function HomeSection({
  title,
  action,
  children,
}: {
  title: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-8">
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-2xl font-bold leading-9">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}
