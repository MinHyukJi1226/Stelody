import { z } from 'zod'
import {
  reviewPreviewSchema,
  type ReviewRequest,
  type ReviewPreview,
} from './review-completion'

export type PendingReview = {
  user: string
  request: ReviewRequest
  titles: string[]
  preview: ReviewPreview
}
export type ReviewRecovery =
  | { state: 'none' }
  | { state: 'pending'; pending: PendingReview }
  | { state: 'unavailable' | 'invalid' }
const uuid = z.uuid()
const key = (user: string) => `stelody-review-completion:${user}`
const receiptSchema = z
  .object({
    user: uuid,
    preview: reviewPreviewSchema,
    request: z
      .object({
        requestId: uuid,
        items: z
          .array(
            z
              .object({ id: uuid, version: z.number().int().nonnegative() })
              .strict(),
          )
          .min(1)
          .max(10000),
        selected: z
          .array(
            z
              .object({
                id: uuid,
              })
              .strict(),
          )
          .min(1)
          .max(100),
      })
      .strict(),
    titles: z.array(z.string()).min(1).max(100),
  })
  .strict()
  .refine(({ request, titles, preview }) => {
    const items = new Set(request.items.map((x) => x.id)),
      selected = new Set(request.selected.map((x) => x.id))
    return (
      preview.items.length === request.items.length &&
      preview.items.every(
        (item, i) =>
          item.id === request.items[i]?.id &&
          item.version === request.items[i]?.version,
      ) &&
      items.size === request.items.length &&
      selected.size === request.selected.length &&
      titles.length === request.selected.length &&
      request.selected.every((x) => items.has(x.id))
    )
  })
export function readPendingReview(user: string): ReviewRecovery {
  let raw: string | null
  try {
    raw = sessionStorage.getItem(key(user))
  } catch {
    return { state: 'unavailable' }
  }
  if (raw === null) return { state: 'none' }
  try {
    const value: unknown = JSON.parse(raw)
    const result = receiptSchema.safeParse(value)
    if (!result.success || result.data.user !== user)
      return { state: 'invalid' }
    // Keep the original keys and array order. The server hashes the whole request body.
    return { state: 'pending', pending: value as PendingReview }
  } catch {
    return { state: 'invalid' }
  }
}
export function storePendingReview(pending: PendingReview) {
  if (!receiptSchema.safeParse(pending).success)
    throw new Error(
      '처리 요청을 보관할 수 없습니다. 선택한 후보를 확인해주세요.',
    )
  const raw = JSON.stringify(pending)
  try {
    sessionStorage.setItem(key(pending.user), raw)
    if (sessionStorage.getItem(key(pending.user)) !== raw)
      throw new Error('storage verification failed')
  } catch {
    throw new Error(
      '이 탭에 처리 요청을 보관할 수 없어 전송하지 않았습니다. 브라우저 저장 설정을 확인하고 다시 시도해주세요.',
    )
  }
}
export function clearPendingReview(user: string) {
  try {
    sessionStorage.removeItem(key(user))
  } catch {
    /* An intact receipt can be safely replayed after recovery. */
  }
}
