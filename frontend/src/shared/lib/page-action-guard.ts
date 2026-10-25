import { createContext, useContext } from 'react'
export type PageAction = () => void
export type ActionGuard = (action: PageAction) => boolean
export const PageActionContext = createContext<{
  run: ActionGuard
  register: (guard: ActionGuard) => () => void
} | null>(null)
export const usePageActions = () => useContext(PageActionContext)
