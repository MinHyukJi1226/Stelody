import { useEffect, useRef, type ReactNode } from 'react'

export function Dialog({
  labelledBy,
  onDismiss,
  busy = false,
  children,
}: {
  labelledBy: string
  onDismiss: () => void
  busy?: boolean
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current!
    const previousFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    dialog.showModal()
    return () => {
      dialog.close()
      document.body.style.overflow = previous
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [])
  return (
    <dialog
      ref={ref}
      aria-labelledby={labelledBy}
      aria-modal="true"
      onCancel={(event) => {
        event.preventDefault()
        if (!busy) onDismiss()
      }}
      className="m-auto max-h-[calc(100dvh-40px)] w-[calc(100vw-40px)] max-w-[520px] overflow-y-auto rounded-2xl border border-line bg-surface p-6 text-ink backdrop:bg-night/50"
    >
      {children}
    </dialog>
  )
}
