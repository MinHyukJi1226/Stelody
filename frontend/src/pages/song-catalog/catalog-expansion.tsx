import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { useMediaQuery } from '@/shared/lib/use-media-query'

export function CatalogExpansion({ children }: { children: ReactNode }) {
  const container = useRef<HTMLDivElement>(null)
  const content = useRef<HTMLDivElement>(null)
  const previousHeight = useRef<number | null>(null)
  const animation = useRef<Animation | null>(null)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')

  useLayoutEffect(() => {
    const outer = container.current
    const inner = content.current
    if (!outer || !inner) return
    const height = inner.getBoundingClientRect().height
    const previous = previousHeight.current
    previousHeight.current = height
    if (reducedMotion) {
      animation.current?.cancel()
      animation.current = null
      outer.style.overflow = ''
      return
    }
    if (previous === null || previous === height) return
    const from = animation.current
      ? outer.getBoundingClientRect().height
      : previous
    animation.current?.cancel()
    outer.style.overflow = 'hidden'
    const next = outer.animate(
      [{ height: `${from}px` }, { height: `${height}px` }],
      { duration: 420, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' },
    )
    animation.current = next
    next.onfinish = () => {
      if (animation.current !== next) return
      outer.style.overflow = ''
      animation.current = null
    }
  })
  useEffect(() => () => animation.current?.cancel(), [])

  return (
    <div ref={container} className="min-w-0">
      <div ref={content} className="min-w-0">
        {children}
      </div>
    </div>
  )
}
