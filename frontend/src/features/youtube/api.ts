import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { safeReturnPath } from '@/features/auth/return-path'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export const youtubePath = '/library/youtube'
export type YouTubeConnection =
  ApiSchemas['com.stelody.export.dto.ExportDtos.Connection']
type Authorization =
  ApiSchemas['com.stelody.export.dto.ExportDtos.Authorization']
type AuthorizationRequest =
  ApiSchemas['com.stelody.export.dto.ExportDtos.AuthorizationRequest']

const connectionSchema = z.object({
  status: z.enum([
    'DISABLED',
    'DISCONNECTED',
    'CONNECTED',
    'RECONNECT_REQUIRED',
    'REVOKING',
  ]),
}) satisfies z.ZodType<YouTubeConnection>
const authorizationSchema = z.object({
  authorizationUrl: z.url(),
  expiresAt: z.iso.datetime({ offset: true }),
}) satisfies z.ZodType<Authorization>

function connection(value: unknown): YouTubeConnection {
  const parsed = connectionSchema.safeParse(value)
  if (!parsed.success) throw new ApiResponseError()
  return parsed.data
}

export async function getYouTubeConnection(signal?: AbortSignal) {
  return connection(
    await apiClient.request<unknown>(
      '/me/youtube/connection',
      signal ? { signal } : {},
    ),
  )
}

export async function authorizeYouTube(
  returnTo: string = youtubePath,
): Promise<Authorization> {
  const body: AuthorizationRequest = {
    returnTo: safeReturnPath(returnTo),
    callbackClient: import.meta.env.DEV ? 'local' : 'default',
  }
  const parsed = authorizationSchema.safeParse(
    await apiClient.request<unknown>('/me/youtube/authorizations', {
      method: 'POST',
      body,
    }),
  )
  if (!parsed.success) throw new ApiResponseError()
  const url = new URL(parsed.data.authorizationUrl)
  if (
    url.origin !== 'https://accounts.google.com' ||
    !url.pathname.startsWith('/o/oauth2/') ||
    url.username ||
    url.password ||
    url.hash ||
    Date.parse(parsed.data.expiresAt) <= Date.now()
  )
    throw new ApiResponseError()
  return parsed.data
}

export async function disconnectYouTube() {
  const result = connection(
    await apiClient.request<unknown>('/me/youtube/connection', {
      method: 'DELETE',
    }),
  )
  if (result.status === 'CONNECTED' || result.status === 'RECONNECT_REQUIRED')
    throw new ApiResponseError()
  return result
}
