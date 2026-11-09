import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
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
  onBeforeNext,
  onPageChange,
  scrollable = false,
  lastPageFull = true,
}: {
  pages: ReactNode[]
  label: string
  square?: boolean
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => Promise<boolean>
  onBeforeNext?: (targetPage: number) => Promise<boolean>
  onPageChange?: (page: number) => void
  scrollable?: boolean
  lastPageFull?: boolean
}) {
  const [page, setPage] = useState(0)
  const [navigating, setNavigating] = useState(false)
  const navigationPending = useRef(false)
  const viewport = useRef<HTMLDivElement>(null)
  const scrollTarget = useRef<number | null>(null)
  const pageCount = pages.length
  const currentPage = Math.min(page, Math.max(0, pageCount - 1))
  function changePage(next: number) {
    if (scrollable) scrollTarget.current = next
    setPage(next)
    onPageChange?.(next)
  }
  useLayoutEffect(() => {
    const element = viewport.current
    const target = scrollTarget.current
    if (!scrollable || !element || target === null) return
    if (Math.abs(element.scrollLeft - target * element.clientWidth) <= 1) {
      scrollTarget.current = null
      return
    }
    element.scrollTo({
      left: target * element.clientWidth,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    })
  }, [currentPage, pageCount, scrollable])

  const position = scrollable
    ? 'absolute top-[calc((100cqw-16px)*0.140625)] -translate-y-1/2 rounded-full shadow-md md:top-[calc((100cqw-48px)*0.09375)] lg:top-[calc((100cqw-120px)*0.046875)]'
    : square
      ? 'absolute top-1/2 -translate-y-1/2 rounded-full shadow-md lg:top-[calc((100cqw-160px)/12)]'
      : arrowPosition

  async function nextPage(targetPage = currentPage + 1) {
    if (navigationPending.current || (loadingMore && !scrollable)) return
    if (onBeforeNext) {
      navigationPending.current = true
      setNavigating(true)
      try {
        if (await onBeforeNext(targetPage)) changePage(targetPage)
      } finally {
        navigationPending.current = false
        setNavigating(false)
      }
      return
    }
    if (currentPage < pageCount - 1) changePage(currentPage + 1)
    else if ((await onLoadMore?.()) && lastPageFull) changePage(currentPage + 1)
  }

  return (
    <div className="@container relative min-w-0">
      <div
        ref={viewport}
        className={
          scrollable
            ? 'overflow-x-auto overscroll-x-contain snap-x snap-mandatory [scrollbar-width:none] [&::-webkit-scrollbar]:hidden'
            : 'overflow-hidden'
        }
        tabIndex={scrollable ? 0 : undefined}
        role={scrollable ? 'group' : undefined}
        aria-label={scrollable ? `${label} 가로 스크롤` : undefined}
        onWheel={
          scrollable
            ? () => {
                scrollTarget.current = null
              }
            : undefined
        }
        onTouchStart={
          scrollable
            ? () => {
                scrollTarget.current = null
              }
            : undefined
        }
        onScroll={
          scrollable
            ? (event) => {
                const element = event.currentTarget
                if (scrollTarget.current !== null) {
                  if (
                    Math.abs(
                      element.scrollLeft -
                        scrollTarget.current * element.clientWidth,
                    ) <= 1
                  )
                    scrollTarget.current = null
                  else return
                }
                const next = Math.round(
                  element.scrollLeft / element.clientWidth,
                )
                if (
                  next >= pageCount ||
                  (next === pageCount - 1 && hasMore && !lastPageFull)
                ) {
                  void nextPage(next)
                  return
                }
                if (next !== currentPage) {
                  setPage(next)
                  onPageChange?.(next)
                }
              }
            : undefined
        }
      >
        <div
          className="flex items-start transition-transform duration-500 ease-in-out motion-reduce:transition-none"
          style={
            scrollable
              ? undefined
              : { transform: `translateX(-${currentPage * 100}%)` }
          }
        >
          {pages.map((content, index) => (
            <div
              key={index}
              className={`w-full min-w-0 shrink-0${scrollable ? ' snap-start' : ''}`}
              inert={index !== currentPage}
              aria-hidden={index !== currentPage}
            >
              {content}
            </div>
          ))}
          {scrollable && hasMore && (
            <div
              className="grid w-full min-w-0 shrink-0 snap-start place-items-center self-stretch"
              aria-hidden="true"
            >
              <span className="text-sm text-muted">노래 불러오는 중…</span>
            </div>
          )}
        </div>
      </div>
      {(pageCount > 1 || hasMore) && (
        <>
          {currentPage > 0 && (
            <Button
              size="icon"
              className={`${position} left-1 size-9 -translate-x-1/2`}
              aria-label={`${label} 이전 곡 보기`}
              disabled={navigating}
              onClick={() => changePage(currentPage - 1)}
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
              disabled={loadingMore || navigating}
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
