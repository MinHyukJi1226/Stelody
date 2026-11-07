import type { ReactNode } from 'react'

export function HomeSection({
  title,
  loading,
  action,
  children,
}: {
  title: string
  loading: boolean
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section
      aria-label={title}
      className={`flex min-w-0 flex-col gap-4${loading ? '' : ' home-section-enter'}`}
    >
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-lg font-bold leading-[1.5]">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}
