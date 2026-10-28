import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export type Artist = ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Artist']
export type ArtistInput =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.ArtistInput']
export type Work = ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Work']
export type WorkInput =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.WorkInput']
const uuid = z.uuid()
const version = z.number().int().nonnegative()
const instant = z.iso.datetime({ offset: true })
export const artistSchema = z.object({
  id: uuid,
  version,
  name: z.string(),
  aliases: z.array(z.string()),
}) satisfies z.ZodType<Artist>
export const workSchema = z.object({
  id: uuid,
  version,
  title: z.string(),
  aliases: z.array(z.string()),
  artistIds: z.array(uuid),
  links: z.array(
    z.object({
      id: uuid,
      platform: z.string(),
      url: z.string(),
      sourceUrl: z.string(),
      checkedAt: instant,
    }),
  ),
  karaoke: z.array(
    z.object({
      id: uuid,
      provider: z.enum(['TJ', 'KY']),
      status: z.enum(['UNKNOWN', 'NOT_LISTED', 'REGISTERED']),
      number: z.string().nullable(),
      sourceUrl: z.string().nullable(),
      checkedAt: instant.nullable(),
    }),
  ),
}) satisfies z.ZodType<Work>
async function read<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(`/admin/${path}`, {
    signal,
    cache: 'no-store',
  })
  signal.throwIfAborted()
  const parsed = schema.safeParse(data)
  if (!parsed.success) throw new ApiResponseError()
  return parsed.data
}
export async function getWork(id: string, signal: AbortSignal) {
  const item = await read(`works/${encodeURIComponent(id)}`, workSchema, signal)
  if (item.id !== id) throw new ApiResponseError()
  return item
}
export async function getArtist(id: string, signal: AbortSignal) {
  const item = await read(
    `artists/${encodeURIComponent(id)}`,
    artistSchema,
    signal,
  )
  if (item.id !== id) throw new ApiResponseError()
  return item
}
export async function getArtists(ids: string[], signal: AbortSignal) {
  return Promise.all([...new Set(ids)].map((id) => getArtist(id, signal)))
}
const artistPageSchema = z.object({
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  hasNext: z.boolean(),
  items: z.array(
    z.object({
      id: uuid,
      name: z.string(),
      version,
      status: z.string().nullable(),
    }),
  ),
})
export async function searchArtists(
  q: string,
  page: number,
  signal: AbortSignal,
) {
  const result = await read(
    `artists?${new URLSearchParams({ q, page: String(page), size: '20' })}`,
    artistPageSchema,
    signal,
  )
  if (result.page !== page) throw new ApiResponseError()
  // List rows do not include aliases. Fetch the displayed artists' details.
  return {
    ...result,
    items: await getArtists(
      result.items.map((x) => x.id),
      signal,
    ),
  }
}
export function workArtistInput(
  work: Work,
  artistIds: string[],
  reason: string,
): WorkInput {
  return {
    version: work.version,
    title: work.title,
    aliases: [...work.aliases],
    artistIds: [...artistIds],
    reason: reason.trim(),
    links: work.links.map(({ platform, url, sourceUrl }) => ({
      platform,
      url,
      sourceUrl,
    })),
    karaoke: work.karaoke.map(({ provider, status, number, sourceUrl }) => ({
      provider,
      status,
      ...(number !== null ? { number } : {}),
      ...(sourceUrl !== null ? { sourceUrl } : {}),
    })),
  }
}
async function write<T>(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  schema: z.ZodType<T>,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(`/admin/${path}`, {
    method,
    body,
    signal,
  })
  signal.throwIfAborted()
  const result = z
    .object({ item: schema, possibleDuplicateIds: z.array(uuid) })
    .safeParse(data)
  if (!result.success) throw new ApiResponseError()
  return result.data.item
}
export async function saveWork(
  work: Work,
  ids: string[],
  reason: string,
  signal: AbortSignal,
) {
  const item = await write(
    `works/${work.id}`,
    'PUT',
    workArtistInput(work, ids, reason),
    workSchema,
    signal,
  )
  if (item.id !== work.id || item.version <= work.version)
    throw new ApiResponseError()
  return item
}
export async function saveArtist(
  id: string,
  input: ArtistInput,
  signal: AbortSignal,
) {
  const item = await write(`artists/${id}`, 'PUT', input, artistSchema, signal)
  if (item.id !== id || item.version <= input.version)
    throw new ApiResponseError()
  return item
}
export async function registerArtist(
  requestId: string,
  input: ArtistInput,
  signal: AbortSignal,
) {
  if (!uuid.safeParse(requestId).success || input.version !== 0)
    throw new Error('등록 요청을 확인해주세요.')
  const data = await apiClient.request<unknown>('/admin/artists', {
    method: 'POST',
    body: input,
    signal,
    headers: { 'Idempotency-Key': requestId },
  })
  signal.throwIfAborted()
  const result = z
    .object({ item: artistSchema, possibleDuplicateIds: z.array(uuid) })
    .safeParse(data)
  if (!result.success) throw new ApiResponseError()
  return result.data.item
}
export type ArtistRegistrationResult =
  ApiSchemas['com.stelody.admin.dto.ArtistRegistrationDtos.Result']
const registrationResultSchema = z
  .object({
    requestId: uuid,
    status: z.enum(['NOT_PROCESSED', 'PROCESSING', 'COMPLETED', 'FAILED']),
    artistId: uuid.nullable(),
    errorCode: z.string().min(1).nullable(),
    createdAt: instant.nullable(),
    expiresAt: instant.nullable(),
    finishedAt: instant.nullable(),
    pollAfterSeconds: z.literal(3).nullable(),
  })
  .refine((value) => {
    if (value.status === 'NOT_PROCESSED')
      return [
        value.artistId,
        value.errorCode,
        value.createdAt,
        value.expiresAt,
        value.finishedAt,
        value.pollAfterSeconds,
      ].every((x) => x === null)
    if (
      !value.createdAt ||
      !value.expiresAt ||
      Date.parse(value.expiresAt) <= Date.parse(value.createdAt)
    )
      return false
    if (value.status === 'PROCESSING')
      return (
        value.artistId === null &&
        value.errorCode === null &&
        value.finishedAt === null &&
        value.pollAfterSeconds === 3
      )
    if (!value.finishedAt || value.pollAfterSeconds !== null) return false
    return value.status === 'COMPLETED'
      ? value.artistId !== null && value.errorCode === null
      : value.artistId === null && value.errorCode !== null
  }) satisfies z.ZodType<ArtistRegistrationResult>
export async function getArtistRegistration(
  requestId: string,
  signal: AbortSignal,
) {
  const result = await read(
    `artist-registrations/${encodeURIComponent(requestId)}`,
    registrationResultSchema,
    signal,
  )
  if (result.requestId !== requestId) throw new ApiResponseError()
  return result
}
export function validateArtist(
  name: string,
  aliases: string[],
  reason: string,
) {
  const errors: Record<string, string> = {}
  if (!name.trim() || name.length > 200)
    errors.name = '아티스트 이름은 1–200자로 입력해주세요.'
  if (aliases.length > 50) errors.aliases = '별칭은 최대 50개입니다.'
  aliases.forEach((x, i) => {
    if (!x.trim() || x.length > 200)
      errors[`aliases[${i}]`] = '별칭은 1–200자로 입력해주세요.'
  })
  if (!reason.trim() || reason.length > 500)
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  return errors
}
