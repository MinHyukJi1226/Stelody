import { useState, type ReactNode } from 'react'
import type { Song } from '@/features/songs/api'
import { Button } from '@/shared/ui/button'
import { HomeSongGrid } from './home-song-grid'

const pageSize = 6
const arrowPosition =
  'absolute top-1/2 -translate-y-1/2 rounded-full shadow-md lg:top-[calc((100cqw-160px)*0.046875)]'

export function HomeSongCarousel({
  songs,
  label,
}: {
  songs: readonly Song[]
  label: string
}) {
  return (
    <HomeCarousel
      label={label}
      pages={Array.from(
        { length: Math.ceil(songs.length / pageSize) },
        (_, index) => (
          <HomeSongGrid
            key={index}
            songs={songs.slice(index * pageSize, (index + 1) * pageSize)}
          />
        ),
      )}
    />
  )
}

export function HomeCarousel({
  pages,
  label,
  square = false,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  lastPageFull = true,
}: {
  pages: ReactNode[]
  label: string
  square?: boolean
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => Promise<boolean>
  lastPageFull?: boolean
}) {
  const [page, setPage] = useState(0)
  const pageCount = pages.length
  const currentPage = Math.min(page, Math.max(0, pageCount - 1))

  const position = square
    ? 'absolute top-1/2 -translate-y-1/2 rounded-full shadow-md lg:top-[calc((100cqw-160px)/12)]'
    : arrowPosition

  async function nextPage() {
    if (currentPage < pageCount - 1) setPage(currentPage + 1)
    else if ((await onLoadMore?.()) && lastPageFull) setPage(currentPage + 1)
  }

  return (
    <div className="@container relative min-w-0">
      <div className="overflow-hidden">
        <div
          className="flex items-start transition-transform duration-500 ease-in-out motion-reduce:transition-none"
          style={{ transform: `translateX(-${currentPage * 100}%)` }}
        >
          {pages.map((content, index) => (
            <div
              key={index}
              className="w-full min-w-0 shrink-0"
              inert={index !== currentPage}
              aria-hidden={index !== currentPage}
            >
              {content}
            </div>
          ))}
        </div>
      </div>
      {(pageCount > 1 || hasMore) && (
        <>
          {currentPage > 0 && (
            <Button
              size="icon"
              className={`${position} left-1 size-9 -translate-x-1/2`}
              aria-label={`${label} 이전 곡 보기`}
              onClick={() => setPage(currentPage - 1)}
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="m15 5-7 7 7 7"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </Button>
          )}
          {(currentPage < pageCount - 1 || hasMore) && (
            <Button
              size="icon"
              className={`${position} right-1 size-9 translate-x-1/2`}
              aria-label={`${label} 다음 곡 보기`}
              disabled={loadingMore}
              onClick={() => void nextPage()}
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="m9 5 7 7-7 7"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </Button>
          )}
          <p className="sr-only" aria-live="polite" aria-atomic="true">
            {label} {currentPage + 1} / {pageCount}페이지
          </p>
        </>
      )}
    </div>
  )
}
