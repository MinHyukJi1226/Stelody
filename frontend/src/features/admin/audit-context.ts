import { createContext } from 'react'
export const AuditGuardContext = createContext({
  dirty: false,
  busy: false,
  uncertain: false,
})
