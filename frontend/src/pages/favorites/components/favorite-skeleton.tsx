export function FavoriteSkeleton() {
  return (
    <div
      role="status"
      aria-label="즐겨찾기 불러오는 중"
      aria-busy="true"
      className="grid gap-8"
    >
      {[0, 1, 2].map((i) => (
        <div key={i} aria-hidden="true" className="flex items-center gap-4">
          <div className="aspect-video w-24 shrink-0 rounded-2xl bg-line md:w-[200px]" />
          <div className="grid min-w-0 flex-1 gap-2">
            <div className="h-4 w-28 max-w-full rounded bg-line" />
            <div className="h-3 w-20 max-w-full rounded bg-line" />
          </div>
          <div className="size-9 shrink-0 rounded-xl bg-line" />
        </div>
      ))}
    </div>
  )
}
