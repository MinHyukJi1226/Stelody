import { z } from 'zod'
import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'
import { adminKeys } from './queries'
import { adminVideoSchema } from './songs'

export type VideoKind = z.infer<typeof adminVideoSchema>['kind']
export type VideoCandidate =
  ApiSchemas['com.stelody.review.dto.ReviewDtos.Item']
type Registration =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Registration']
const instant = z.iso.datetime({ offset: true }).nullable()
export const candidateSchema = z.object({
  id: z.uuid(),
  version: z.number().int().nonnegative(),
  youtubeId: z.string().regex(/^[A-Za-z0-9_-]{11}$/),
  channelId: z.uuid().nullable(),
  channelName: z.string().nullable(),
  title: z.string().nullable(),
  publishedAt: instant,
  thumbnailUrl: z.string().nullable(),
  durationSeconds: z.number().nullable(),
  observedAt: instant,
  availability: z.string(),
  disposition: z.string(),
  suggestedType: z.string(),
  ruleVersion: z.string(),
  decisionReason: z.string(),
  status: z.string(),
  reviewNote: z.string().nullable(),
  firstSeenAt: z.iso.datetime({ offset: true }),
  sourceExpired: z.boolean(),
  registeredVideoId: z.uuid().nullable(),
  // Older deployments omit this field; never infer the song ID from the video ID.
  registeredSongId: z.uuid().nullable().default(null),
}) satisfies z.ZodType<VideoCandidate>
const pageSchema = z.object({
  items: z.array(candidateSchema),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  hasNext: z.boolean(),
})
async function read<T>(
  path: string,
  signal: AbortSignal,
  schema: z.ZodType<T>,
) {
  const data = await apiClient.request<unknown>(path, {
    signal,
    cache: 'no-store',
  })
  const parsed = schema.safeParse(data)
  if (!parsed.success) throw new ApiResponseError()
  signal.throwIfAborted()
  return parsed.data
}
export const videoCandidatesOptions = (user: string, page: number) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'video-candidates', page],
    queryFn: async ({ signal }) => {
      const result = await read(
        `/admin/reviews?${new URLSearchParams({ status: 'PENDING', page: String(page), size: '20' })}`,
        signal,
        pageSchema,
      )
      if (
        result.page !== page ||
        result.size !== 20 ||
        result.items.some((x) => x.status !== 'PENDING')
      )
        throw new ApiResponseError()
      return result
    },
    retry: false,
    gcTime: 0,
    staleTime: 0,
  })
export async function getVideoCandidate(id: string, signal: AbortSignal) {
  const result = await read(
    `/admin/reviews/${encodeURIComponent(id)}`,
    signal,
    candidateSchema,
  )
  if (result.id !== id) throw new ApiResponseError()
  return result
}
export async function attachSongVideo(
  candidate: VideoCandidate,
  body: Registration,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(
    `/admin/reviews/${encodeURIComponent(candidate.id)}/registration`,
    { method: 'POST', body, signal },
  )
  const parsed = adminVideoSchema.safeParse(data)
  if (
    !parsed.success ||
    parsed.data.songId !== body.songId ||
    parsed.data.youtubeId !== candidate.youtubeId ||
    parsed.data.kind !== body.kind
  )
    throw new ApiResponseError()
  signal.throwIfAborted()
  return parsed.data
}
export function candidateUnavailable(candidate: VideoCandidate) {
  if (candidate.status !== 'PENDING' || candidate.registeredVideoId)
    return '이미 등록된 후보입니다.'
  if (candidate.sourceExpired)
    return '관측 30일 초과 · 후보 재확인이 필요합니다.'
  if (candidate.availability !== 'PUBLIC') return '현재 공개 영상이 아닙니다.'
  if (
    !candidate.title?.trim() ||
    !candidate.publishedAt ||
    !candidate.observedAt
  )
    return '후보의 공개 정보가 부족합니다.'
  if (Date.parse(candidate.publishedAt) > Date.now())
    return '아직 공개되지 않은 영상입니다.'
  return ''
}
