import type { ReactNode } from 'react'

export function AccountLayout({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <div className="flex min-w-0 flex-1 flex-col px-5 md:px-8">
      <section className="flex min-w-0 flex-1 flex-col items-start gap-8 py-8 md:py-10">
        <h1 className="w-full break-words text-2xl font-bold leading-[1.5] md:text-[32px]">
          {title}
        </h1>
        {children}
      </section>
    </div>
  )
}

export function AccountCard({
  children,
  loading = false,
}: {
  children: ReactNode
  loading?: boolean
}) {
  return (
    <section
      aria-label="계정 상태"
      aria-busy={loading}
      className="flex w-full min-w-0 max-w-[640px] flex-col items-start gap-4 text-sm leading-[1.5]"
    >
      {children}
    </section>
  )
}

export function AccountEmail({ email }: { email: string }) {
  return (
    <div className="flex w-full min-w-0 flex-col gap-3">
      <h2 className="text-lg font-bold md:text-xl">내계정</h2>
      <p className="break-all text-base">{email}</p>
    </div>
  )
}

export function AccountSkeleton() {
  return (
    <div
      role="status"
      aria-label="계정 정보 조회 중"
      className="flex w-full flex-col gap-3"
    >
      <div className="h-4 w-[36%] rounded-lg bg-line" />
      <div className="h-4 w-[48%] rounded-lg bg-line" />
      <div className="h-4 w-[32%] rounded-lg bg-line" />
    </div>
  )
}
