import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ToastContext, type ToastKind } from './toast-context'

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{
    message: string
    kind: ToastKind
  } | null>(null)
  const notify = useCallback(
    (message: string, kind: ToastKind = 'success') =>
      setToast({ message, kind }),
    [],
  )
  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(null), 6000)
    return () => window.clearTimeout(timer)
  }, [toast])
  return (
    <ToastContext value={notify}>
      {children}
      {toast && (
        <div
          key={toast.message}
          role={toast.kind === 'error' ? 'alert' : 'status'}
          className={`fixed bottom-6 right-6 z-50 w-[calc(100%-48px)] max-w-[420px] rounded-2xl border border-line bg-surface p-6 text-base leading-[1.5] shadow-lg ${toast.kind === 'error' ? 'text-error' : 'text-ink'}`}
        >
          {toast.message}
        </div>
      )}
    </ToastContext>
  )
}
