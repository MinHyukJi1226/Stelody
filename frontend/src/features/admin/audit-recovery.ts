import { z } from 'zod'
const target = z.object({
  kind: z.enum([
    'songs',
    'works',
    'artists',
    'members',
    'channels',
    'videos',
    'reviews',
    'collection-rules',
  ]),
  id: z.uuid().nullable(),
  name: z.string(),
})
export const auditRecoverySchema = z.object({
  target: target.nullable(),
  page: z.number().int().min(0).max(10000),
  entryId: z.uuid().nullable(),
})
export type AuditRecovery = z.infer<typeof auditRecoverySchema>
export function readAuditRecovery(key: string) {
  try {
    const raw = sessionStorage.getItem(key)
    return raw ? auditRecoverySchema.parse(JSON.parse(raw)) : null
  } catch {
    return null
  }
}
export function saveAuditRecovery(key: string, value: AuditRecovery | null) {
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else sessionStorage.removeItem(key)
  } catch {
    /* Read-only viewing remains usable when storage is unavailable. */
  }
}
