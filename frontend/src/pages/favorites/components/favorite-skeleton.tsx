export function FavoriteSkeleton() {
  return (
    <div
      role="status"
      aria-label="즐겨찾기 불러오는 중"
      aria-busy="true"
      className="flex min-w-0 flex-col gap-8"
    >
      <h1 className="sr-only">내 즐겨찾기</h1>
      <div
        aria-hidden="true"
        className="h-7 w-[180px] max-w-full md:w-[340px] rounded-lg bg-line"
      />
      <div aria-hidden="true" className="flex gap-2">
        <div className="h-11 w-25 rounded-lg bg-line" />
        <div className="h-11 w-30 rounded-lg bg-line" />
      </div>
      <div
        aria-hidden="true"
        className="grid grid-cols-1 items-start gap-6 md:grid-cols-2 lg:grid-cols-3"
      >
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className={`${i === 2 ? 'hidden md:flex' : 'flex'} flex-col gap-4 rounded-2xl bg-surface p-4`}
          >
            <div className="h-[180px] rounded-lg md:h-[158px] bg-line" />
            <div className="h-5 w-3/4 rounded-lg bg-line" />
            <div className="h-3.5 w-1/2 rounded-lg bg-line" />
            <div className="h-3 w-2/3 rounded-lg bg-line" />
          </div>
        ))}
      </div>
    </div>
  )
}
