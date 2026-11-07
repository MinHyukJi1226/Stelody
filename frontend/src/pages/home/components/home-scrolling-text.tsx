import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useMediaQuery } from '@/shared/lib/use-media-query'
import { cn } from '@/shared/lib/utils'

export function HomeScrollingText({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  const viewport = useRef<HTMLSpanElement>(null)
  const content = useRef<HTMLSpanElement>(null)
  const track = useRef<HTMLSpanElement>(null)
  const [distance, setDistance] = useState(0)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const scrolling = distance > 0 && !reducedMotion

  useEffect(() => {
    const container = viewport.current
    const text = content.current
    if (!container || !text) return
    const update = () => {
      setDistance(
        text.scrollWidth > container.clientWidth + 1
          ? text.scrollWidth + 32
          : 0,
      )
    }
    const observer = new ResizeObserver(update)
    observer.observe(container)
    observer.observe(text)
    update()
    return () => observer.disconnect()
  }, [children])

  useEffect(() => {
    if (!scrolling || !track.current) return
    const animation = track.current.animate(
      [
        { transform: 'translateX(0)' },
        { transform: `translateX(-${distance}px)` },
      ],
      {
        duration: (distance / 24) * 1000,
        iterations: Infinity,
        easing: 'linear',
      },
    )
    return () => animation.cancel()
  }, [distance, scrolling])

  return (
    <span
      ref={viewport}
      className={cn(
        'block min-w-0 overflow-hidden whitespace-nowrap [scrollbar-width:none] motion-reduce:overflow-x-auto [&::-webkit-scrollbar]:hidden',
        className,
      )}
    >
      <span ref={track} className="flex w-max items-center gap-8">
        <span ref={content} className="block shrink-0">
          {children}
        </span>
        {scrolling && (
          <span aria-hidden="true" className="block shrink-0">
            {children}
          </span>
        )}
      </span>
    </span>
  )
}
