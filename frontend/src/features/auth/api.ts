import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiError, ApiResponseError } from '@/shared/api/errors'
import type { CurrentUser } from '@/shared/api/types'
import { oauthReturnPath } from './return-path'

const currentUserSchema = z.object({
  id: z.uuid(),
  email: z.string().min(1),
  role: z.string().min(1),
  status: z.string().min(1),
}) satisfies z.ZodType<CurrentUser>

export async function getSession(
  signal?: AbortSignal,
): Promise<CurrentUser | null> {
  try {
    const data = await apiClient.request<unknown>(
      '/me',
      signal ? { signal } : {},
    )
    const parsed = currentUserSchema.safeParse(data)
    if (!parsed.success) throw new ApiResponseError()
    return parsed.data
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

export function googleLoginUrl(returnTo: string) {
  const query = new URLSearchParams({ returnTo: oauthReturnPath(returnTo) })
  if (import.meta.env.DEV) query.set('loginClient', 'local')
  return apiClient.url(`/auth/google?${query}`)
}

export function logout() {
  return apiClient.request<void>('/auth/logout', { method: 'POST' })
}
