import { z } from 'zod'
import { queryOptions } from '@tanstack/react-query'
import {
  candidateSchema,
  getVideoCandidate,
  type VideoCandidate,
} from './song-videos'
import { adminKeys } from './queries'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
const reviewPageSchema = z.object({
  page: z.number().int().nonnegative(),
  size: z.number().int().min(1).max(50),
  hasNext: z.boolean(),
  items: z.array(candidateSchema),
})
export type ReviewChange = {
  version: number
  status: 'PENDING' | 'IGNORED'
  reason: string
}
export const reviewItemOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'review-item', id],
    queryFn: ({ signal }) => getVideoCandidate(id, signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
export function validateReview(status: string, reason: string) {
  const errors: Record<string, string> = {}
  if (!['PENDING', 'IGNORED'].includes(status))
    errors.status = '검토 대기 또는 제외를 선택해주세요.'
  if (!reason.trim() || reason.length > 500)
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  return errors
}
export async function writeReview(
  id: string,
  input: ReviewChange,
  signal: AbortSignal,
): Promise<VideoCandidate> {
  const data = await apiClient.request<unknown>(
    `/admin/reviews/${encodeURIComponent(id)}`,
    { method: 'PATCH', body: input, signal },
  )
  signal.throwIfAborted()
  const p = candidateSchema.safeParse(data)
  if (
    !p.success ||
    p.data.id !== id ||
    p.data.version < input.version ||
    p.data.status !== input.status
  )
    throw new ApiResponseError()
  return p.data
}
export function reviewReturnPath(value: string | null) {
  try {
    const url = new URL(
      value ?? '/admin/review-items',
      'https://stelody.invalid',
    )
    if (
      url.origin === 'https://stelody.invalid' &&
      url.pathname === '/admin/review-items'
    )
      return url.pathname + url.search
  } catch {
    /* Fall back to the individual review list. */
  }
  return '/admin/review-items'
}
export const reviewDetailPath = (id: string, returnTo: string) =>
  `/admin/review-items/${encodeURIComponent(id)}?${new URLSearchParams({ returnTo: reviewReturnPath(returnTo) })}`
export const reviewStatusLabels: Record<string, string> = {
  PENDING: '검토 대기',
  IGNORED: '제외',
  REGISTERED: '등록 완료',
}

export const reviewListOptions = (
  user: string,
  status: string,
  disposition: string,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [
      ...adminKeys(user),
      'review-items',
      status,
      disposition,
      page,
      size,
    ],
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({
        status,
        page: String(page),
        size: String(size),
      })
      if (disposition) query.set('disposition', disposition)
      const data = await apiClient.request<unknown>(`/admin/reviews?${query}`, {
        signal,
        cache: 'no-store',
      })
      signal.throwIfAborted()
      const parsed = reviewPageSchema.safeParse(data)
      if (
        !parsed.success ||
        parsed.data.page !== page ||
        parsed.data.size !== size ||
        parsed.data.items.some(
          (x) =>
            (status !== 'ALL' && x.status !== status) ||
            (disposition && x.disposition !== disposition),
        )
      )
        throw new ApiResponseError()
      return parsed.data
    },
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
