import { useState } from 'react'
import { cn } from '@/shared/lib/utils'

export function Artwork({
  src,
  className,
  avatar = false,
}: {
  src: string | null
  className?: string
  avatar?: boolean
}) {
  const [failedSource, setFailedSource] = useState<string | null>(null)
  return (
    <span
      className={cn(
        'flex shrink-0 items-center justify-center overflow-hidden bg-line text-xs text-muted',
        className,
      )}
      aria-hidden="true"
    >
      {src && failedSource !== src ? (
        <img
          src={src}
          alt=""
          loading="lazy"
          className="size-full object-cover"
          onError={() => setFailedSource(src)}
        />
      ) : avatar ? (
        '♪'
      ) : (
        '이미지 준비 중'
      )}
    </span>
  )
}
