import { useEffect, useRef, useState } from 'react'
import { Button } from '@/shared/ui/button'

export function LibraryDeleteButton({
  label,
  pending,
  onConfirm,
}: {
  label: string
  pending: boolean
  onConfirm: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!confirming) return
    function dismiss(event: PointerEvent) {
      if (
        event.target instanceof Node &&
        !button.current?.contains(event.target)
      )
        setConfirming(false)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [confirming])

  return (
    <Button
      ref={button}
      size="icon"
      variant="destructive"
      className={`h-9 border-0 rounded-xl bg-[#ff4d4f] text-white ${confirming ? 'w-auto max-w-32 break-keep whitespace-normal px-2 text-xs sm:max-w-none sm:whitespace-nowrap sm:px-3' : 'w-9'}`}
      aria-label={confirming ? `${label} 정말 삭제하시겠습니까?` : label}
      aria-busy={pending}
      disabled={pending}
      onBlur={() => setConfirming(false)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setConfirming(false)
        if (event.key === 'Enter' && event.repeat) event.preventDefault()
      }}
      onClick={() => {
        if (!confirming) {
          setConfirming(true)
          return
        }
        setConfirming(false)
        onConfirm()
      }}
    >
      {confirming ? (
        '정말 삭제하시겠습니까?'
      ) : (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M3 6h18M9 6V3h6v3m4 0-1 14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1L5 6m5 4v7m4-7v7" />
        </svg>
      )}
    </Button>
  )
}
