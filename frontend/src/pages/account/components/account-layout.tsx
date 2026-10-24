import type { ReactNode } from 'react'
import { SiteFooter } from '@/shared/ui/site-footer'

export function AccountLayout({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section className="flex min-w-0 flex-1 flex-col items-center gap-6">
        <h1 className="w-full break-words text-[32px] font-bold leading-[1.5]">
          {title}
        </h1>
        {children}
      </section>
      <SiteFooter />
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
      className="flex w-full min-w-0 max-w-[640px] flex-col items-start gap-5 rounded-2xl bg-surface p-6 text-sm leading-[1.5]"
    >
      {children}
    </section>
  )
}

export function AccountEmail({ email }: { email: string }) {
  return (
    <div className="flex w-full min-w-0 flex-col gap-2 rounded-xl bg-bg p-3">
      <p className="text-xs text-muted">로그인한 계정</p>
      <p className="break-all font-medium">{email}</p>
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
