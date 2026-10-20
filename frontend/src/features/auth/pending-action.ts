import { z } from 'zod'
import { safeReturnPath } from './return-path'

const key = 'stelody-login-action'
const lifetime = 5 * 60_000
const actionSchema = z.object({
  kind: z.literal('favorite'),
  songId: z.uuid(),
  favorited: z.boolean(),
  returnTo: z.string(),
  createdAt: z.number(),
  expectedUserId: z.uuid().optional(),
  readyUserId: z.uuid().optional(),
})
export type PendingAction = z.infer<typeof actionSchema>

export function readPendingAction(): PendingAction | null {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return null
    const parsed = actionSchema.safeParse(JSON.parse(raw))
    if (
      !parsed.success ||
      Date.now() - parsed.data.createdAt > lifetime ||
      parsed.data.createdAt > Date.now() ||
      safeReturnPath(parsed.data.returnTo) !== parsed.data.returnTo
    ) {
      sessionStorage.removeItem(key)
      return null
    }
    return parsed.data
  } catch {
    try {
      sessionStorage.removeItem(key)
    } catch {
      /* Storage may be blocked. */
    }
    return null
  }
}

export function storePendingAction(
  action: Omit<PendingAction, 'createdAt' | 'readyUserId' | 'kind'>,
) {
  try {
    sessionStorage.setItem(
      key,
      JSON.stringify({ ...action, kind: 'favorite', createdAt: Date.now() }),
    )
    return true
  } catch {
    return false
  }
}

export function preparePendingAction(userId: string, returnTo: string) {
  const action = readPendingAction()
  if (!action || action.returnTo !== returnTo) return
  try {
    if (action.expectedUserId && action.expectedUserId !== userId) {
      sessionStorage.removeItem(key)
      return
    }
    sessionStorage.setItem(
      key,
      JSON.stringify({ ...action, readyUserId: userId }),
    )
  } catch {
    /* Login remains available if browser storage is blocked. */
  }
}

export function consumePendingAction(userId: string, returnTo: string) {
  const action = readPendingAction()
  if (!action || action.readyUserId !== userId || action.returnTo !== returnTo)
    return null
  try {
    // Consume before sending so remounts, refreshes and StrictMode cannot replay it.
    sessionStorage.removeItem(key)
    return action
  } catch {
    return null
  }
}
