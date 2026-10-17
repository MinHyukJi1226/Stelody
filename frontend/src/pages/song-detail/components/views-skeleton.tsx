export function ViewsSkeleton() {
  return (
    <div
      role="status"
      aria-label="조회수 불러오는 중"
      className="flex h-[278px] flex-col justify-center gap-4 px-3 py-6 md:h-[304px] md:px-[76px]"
    >
      <div
        className="flex h-[180px] flex-col justify-between rounded-xl bg-bg p-3 md:h-[210px]"
        aria-hidden="true"
      >
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className="h-2 rounded bg-line" />
        ))}
      </div>
      <p className="text-xs text-muted">기간별 데이터 불러오는 중…</p>
    </div>
  )
}
