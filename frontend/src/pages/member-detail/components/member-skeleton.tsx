import { cn } from '@/shared/lib/utils'

export function MemberSkeleton() {
  return (
    <div
      role="status"
      aria-label="멤버 상세 불러오는 중"
      aria-busy="true"
      className="flex flex-col gap-8"
    >
      <div aria-hidden="true" className="flex items-start gap-5">
        <div className="size-16 shrink-0 rounded-full bg-line md:size-24" />
        <div className="flex flex-1 flex-col gap-4">
          <div className="h-6 rounded-lg bg-line" />
          <div className="h-4 w-[130px] max-w-full rounded-lg bg-line md:w-[200px]" />
        </div>
      </div>
      <div
        aria-hidden="true"
        className="grid grid-cols-1 gap-x-6 gap-y-8 md:grid-cols-2 lg:grid-cols-4"
      >
        {Array.from({ length: 4 }, (_, index) => (
          <div
            key={index}
            className={cn(
              'min-w-0 flex-col gap-4 rounded-2xl bg-surface p-4',
              index < 2 ? 'flex' : 'hidden md:flex',
            )}
          >
            <div className="h-[180px] w-full rounded-lg bg-line md:h-[158px]" />
            <div className="h-5 w-[calc(100%_-_32px)] rounded-lg bg-line" />
            <div className="h-3.5 w-[140px] max-w-full rounded-lg bg-line" />
            <div className="h-3 w-[190px] max-w-full rounded-lg bg-line" />
          </div>
        ))}
      </div>
    </div>
  )
}
