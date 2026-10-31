import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError, ApiError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export type ReviewPreview =
  ApiSchemas['com.stelody.review.dto.ReviewDtos.CompletionPreview']
export type ReviewCandidate = ReviewPreview['items'][number]
export type ReviewRequest =
  ApiSchemas['com.stelody.review.dto.ReviewDtos.Complete']
export type ReviewResult =
  ApiSchemas['com.stelody.review.dto.ReviewDtos.CompletionResult']
const instant = z.iso.datetime({ offset: true }).nullable()
const candidateSchema = z.object({
  id: z.uuid(),
  version: z.number().int().nonnegative(),
  youtubeId: z.string(),
  channelId: z.uuid().nullable(),
  channelName: z.string().nullable(),
  title: z.string().nullable(),
  publishedAt: instant,
  thumbnailUrl: z.string().nullable(),
  durationSeconds: z.number().nullable(),
  observedAt: instant,
  availability: z.string(),
  disposition: z.literal('REVIEW'),
  suggestedType: z.string(),
  ruleVersion: z.string(),
  decisionReason: z.string(),
  status: z.literal('PENDING'),
  reviewNote: z.string().nullable(),
  firstSeenAt: z.iso.datetime({ offset: true }),
  sourceExpired: z.literal(false),
  registeredVideoId: z.uuid().nullable(),
  // Older deployments omit this field; never infer the song ID from the video ID.
  registeredSongId: z.uuid().nullable().default(null),
})
export const reviewPreviewSchema = z
  .object({
    items: z.array(candidateSchema).max(10000),
    hasMore: z.boolean(),
    channelMembers: z.record(z.uuid(), z.uuid()),
  })
  .refine(
    (data) =>
      new Set(data.items.map((item) => item.id)).size === data.items.length,
  )

export async function getReviewCompletion(
  signal: AbortSignal,
): Promise<ReviewPreview> {
  const data = await apiClient.request<unknown>('/admin/reviews/completion', {
    signal,
    cache: 'no-store',
  })
  const parsed = reviewPreviewSchema.safeParse(data)
  if (!parsed.success) throw new ApiResponseError()
  signal.throwIfAborted()
  return parsed.data
}
const resultSchema = z.object({
  registeredSongs: z
    .array(z.object({ reviewId: z.uuid(), songId: z.uuid() }))
    .min(1)
    .max(100),
  deletedCount: z.number().int().nonnegative(),
  replayed: z.boolean(),
})
export function parseReviewResult(
  value: unknown,
  request: ReviewRequest,
): ReviewResult {
  const parsed = resultSchema.safeParse(value)
  if (!parsed.success) throw new ApiResponseError()
  const result = parsed.data
  const ids = new Set(result.registeredSongs.map((song) => song.reviewId))
  if (
    ids.size !== request.selected.length ||
    result.registeredSongs.length !== ids.size ||
    request.selected.some((song) => !ids.has(song.id)) ||
    new Set(result.registeredSongs.map((song) => song.songId)).size !==
      ids.size ||
    result.deletedCount > request.items.length - request.selected.length ||
    (result.replayed && result.deletedCount !== 0)
  )
    throw new ApiResponseError()
  return result
}
export async function completeReview(
  request: ReviewRequest,
  signal: AbortSignal,
) {
  const result = await apiClient.request<unknown>('/admin/reviews/completion', {
    method: 'POST',
    body: request,
    signal,
  })
  return parseReviewResult(result, request)
}
export function reviewRequest(
  preview: ReviewPreview,
  selected: readonly string[],
  requestId: string,
): ReviewRequest {
  const chosen = new Set(selected)
  const items = preview.items.filter((item) => chosen.has(item.id))
  if (
    !selected.length ||
    selected.length > 100 ||
    chosen.size !== selected.length ||
    items.length !== selected.length
  )
    throw new Error('등록할 후보를 1~100개 선택해주세요.')
  return {
    requestId,
    items: preview.items.map(({ id, version }) => ({ id, version })),
    selected: items.map(({ id }) => ({ id })),
  }
}
const classificationMessages: Record<string, string> = {
  CONTENT_TYPE_CONFLICT: '기존 곡 유형 제안과 현재 분류가 일치하지 않습니다.',
  CONTENT_REQUIRES_REVIEW: '영상 내용을 추가로 검토해야 합니다.',
  TITLE_REQUIRES_EDIT: '영상 제목으로 등록할 곡명을 구성할 수 없습니다.',
}
export function reviewFieldErrors(error: ApiError, request: ReviewRequest) {
  const candidates: Record<string, string[]> = {}
  const general: string[] = []
  for (const item of error.fieldErrors) {
    const match = /^selected\[(\d+)\]\.id$/.exec(item.field)
    const id = match ? request.selected[Number(match[1])]?.id : undefined
    const message = classificationMessages[item.code] ?? item.message
    if (id) (candidates[id] ??= []).push(message)
    else general.push(message)
  }
  return { candidates, general }
}
