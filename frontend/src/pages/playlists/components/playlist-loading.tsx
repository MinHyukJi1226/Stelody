export function PlaylistLoading() {
  return (
    <div
      role="status"
      aria-label="플레이리스트 불러오는 중"
      aria-busy="true"
      className="flex flex-col gap-8"
    >
      <div
        aria-hidden="true"
        className="h-7 w-[180px] max-w-full rounded-lg bg-line md:w-[340px]"
      />
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          aria-hidden="true"
          className="flex gap-4 rounded-2xl bg-surface p-4"
        >
          <div className="size-[72px] shrink-0 rounded-lg bg-line" />
          <div className="flex min-w-0 flex-1 flex-col gap-3">
            <div className="h-5 rounded-lg bg-line" />
            <div className="h-3.5 w-full max-w-[380px] rounded-lg bg-line" />
            <div className="h-3 w-[90px] rounded-lg bg-line" />
          </div>
        </div>
      ))}
    </div>
  )
}
