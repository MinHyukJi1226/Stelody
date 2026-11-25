import { useEffect, useState, type ReactNode } from 'react'
import { cn } from '@/shared/lib/utils'

const RETRY_DELAYS = [1000, 2000] as const

function RetryableImage({
  src,
  fallback,
}: {
  src: string
  fallback: ReactNode
}) {
  const [attempt, setAttempt] = useState(0)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const delay = RETRY_DELAYS[attempt]
    if (!failed || delay === undefined) return

    const timer = window.setTimeout(() => {
      setAttempt((current) => current + 1)
      setFailed(false)
    }, delay)
    return () => window.clearTimeout(timer)
  }, [attempt, failed])

  if (failed) return fallback

  return (
    <img
      key={attempt}
      src={src}
      alt=""
      loading="lazy"
      className="size-full object-cover"
      onError={() => {
        if (import.meta.env.DEV) {
          console.warn('[Artwork] 이미지 로딩 실패', {
            src,
            attempt: attempt + 1,
            maxAttempts: RETRY_DELAYS.length + 1,
          })
        }
        setFailed(true)
      }}
    />
  )
}

export function Artwork({
  src,
  className,
  avatar = false,
  fallbackText,
}: {
  src: string | null
  className?: string
  avatar?: boolean
  fallbackText?: string
}) {
  const fallback = fallbackText ?? (avatar ? '♪' : '이미지 준비 중')
  return (
    <span
      className={cn(
        'flex shrink-0 items-center justify-center overflow-hidden bg-line text-xs text-muted',
        className,
      )}
      aria-hidden="true"
    >
      {src ? (
        <RetryableImage key={src} src={src} fallback={fallback} />
      ) : (
        fallback
      )}
    </span>
  )
}
