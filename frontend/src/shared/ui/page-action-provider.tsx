import { useMemo, useRef, type ReactNode } from 'react'
import {
  PageActionContext,
  type ActionGuard,
} from '@/shared/lib/page-action-guard'

/** Keeps account actions under the same unsaved-change guard as router navigation. */
export function PageActionProvider({ children }: { children: ReactNode }) {
  const active = useRef<ActionGuard | null>(null)
  const value = useMemo(
    () => ({
      run: (action: () => void) => {
        if (active.current) return active.current(action)
        action()
        return false
      },
      register: (guard: ActionGuard) => {
        active.current = guard
        return () => {
          if (active.current === guard) active.current = null
        }
      },
    }),
    [],
  )
  return (
    <PageActionContext.Provider value={value}>
      {children}
    </PageActionContext.Provider>
  )
}
