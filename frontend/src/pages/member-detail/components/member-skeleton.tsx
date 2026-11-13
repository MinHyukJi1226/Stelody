import { CompactSongSkeleton } from '@/features/songs/compact-song-grid'

export function MemberSkeleton() {
  return (
    <div
      role="status"
      aria-label="멤버 상세 불러오는 중"
      aria-busy="true"
      className="flex min-w-0 flex-col gap-8"
    >
      <div aria-hidden="true" className="flex items-center gap-5">
        <div className="size-24 shrink-0 rounded-full bg-line" />
        <div className="grid w-48 max-w-full gap-2">
          <div className="h-6 rounded-lg bg-line" />
          <div className="h-4 w-1/2 rounded bg-line" />
          <div className="h-8 w-28 rounded bg-line" />
        </div>
      </div>
      {Array.from({ length: 3 }, (_, i) => (
        <div key={i} className="grid min-w-0 gap-3">
          <div aria-hidden="true" className="h-[27px] w-28 rounded bg-line" />
          <CompactSongSkeleton
            count={6}
            className="grid-cols-2 gap-x-4 md:grid-cols-3 lg:grid-cols-6 md:gap-x-6"
          />
        </div>
      ))}
    </div>
  )
}
