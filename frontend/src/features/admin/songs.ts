import { z } from 'zod'
import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'
import { adminKeys } from './queries'

export type AdminSong =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Song']
export type SongInput =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.SongInput']
export type SongList =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.SongPage']
export type EntityPage =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Page']
export type EntityKind = 'members' | 'artists' | 'works'
const uuid = z.uuid()
const instant = z.iso.datetime({ offset: true }).nullable()
const visibility = z.enum(['DRAFT', 'PUBLISHED', 'HIDDEN'])
const video = z.object({
  id: uuid,
  version: z.number().int().nonnegative(),
  songId: uuid,
  youtubeId: z.string(),
  kind: z.enum(['OFFICIAL_MV', 'OFFICIAL_COVER', 'AUDIO', 'REUPLOAD', 'OTHER']),
  availability: z.enum([
    'PUBLIC',
    'UNLISTED',
    'PRIVATE',
    'DELETED',
    'UNAVAILABLE',
  ]),
  sourceTitle: z.string().nullable(),
  sourcePublishedAt: instant,
  sourceThumbnailUrl: z.string().nullable(),
  sourceObservedAt: instant,
  publishedAt: instant,
  thumbnailUrl: z.string().nullable(),
  embeddable: z.boolean(),
})
const link = z.object({
  id: uuid,
  platform: z.string(),
  url: z.string(),
  sourceUrl: z.string(),
  checkedAt: z.iso.datetime({ offset: true }),
})
const karaoke = z.object({
  id: uuid,
  provider: z.enum(['TJ', 'KY']),
  status: z.enum(['UNKNOWN', 'NOT_LISTED', 'REGISTERED']),
  number: z.string().nullable(),
  sourceUrl: z.string().nullable(),
  checkedAt: instant,
})
export const songSchema = z.object({
  id: uuid,
  version: z.number().int().nonnegative(),
  title: z.string(),
  type: z.enum(['ORIGINAL', 'COVER']),
  workId: uuid.nullable(),
  visibility,
  representativeVideoId: uuid.nullable(),
  aliases: z.array(z.string()),
  memberIds: z.array(uuid),
  externalArtistIds: z.array(uuid),
  isSpecialEvent: z.boolean(),
  specialEventLabel: z.string().nullable(),
  searchVisibility: z.enum(['UNCHECKED', 'NORMAL', 'DIFFICULT']),
  recommendedSearchQuery: z.string().nullable(),
  searchCheckedAt: instant,
  videos: z.array(video),
  links: z.array(link),
  karaoke: z.array(karaoke),
  missingFields: z.array(z.string()),
}) satisfies z.ZodType<AdminSong>
const pagination = {
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  hasNext: z.boolean(),
}
const songListSchema = z.object({
  ...pagination,
  items: z.array(
    z.object({
      id: uuid,
      name: z.string(),
      version: z.number().int().nonnegative(),
      status: visibility,
      participants: z.array(
        z.object({
          id: uuid,
          name: z.string(),
          kind: z.enum(['MEMBER', 'EXTERNAL']),
          confirmed: z.boolean(),
        }),
      ),
      discoveredAt: instant,
      missingFields: z.array(z.string()),
      informationComplete: z.boolean(),
    }),
  ),
}) satisfies z.ZodType<SongList>
const entityPageSchema = z.object({
  ...pagination,
  items: z.array(
    z.object({
      id: uuid,
      name: z.string(),
      version: z.number(),
      status: z.string().nullable(),
    }),
  ),
})
const workSchema = z.object({
  id: uuid,
  title: z.string(),
  artistIds: z.array(uuid),
})
const namedSchema = z.object({ id: uuid, name: z.string() })
async function read<T>(
  path: string,
  signal: AbortSignal,
  schema: z.ZodType<T>,
) {
  const data = await apiClient.request<unknown>(`/admin/${path}`, {
    signal,
    cache: 'no-store',
  })
  const parsed = schema.safeParse(data)
  if (!parsed.success) throw new ApiResponseError()
  signal.throwIfAborted()
  return parsed.data
}
export const getAdminSong = async (id: string, signal: AbortSignal) => {
  const song = await read(`songs/${encodeURIComponent(id)}`, signal, songSchema)
  if (song.id !== id) throw new ApiResponseError()
  return song
}
export const songListOptions = (
  user: string,
  q: string,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'songs', q, page, size],
    queryFn: ({ signal }) =>
      read(
        `songs?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
        signal,
        songListSchema,
      ),
    retry: false,
    gcTime: 0,
    staleTime: 0,
  })
export const songOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'song', id],
    queryFn: ({ signal }) => getAdminSong(id, signal),
    retry: false,
    gcTime: 0,
    staleTime: Infinity,
  })
export const entityOptions = (
  user: string,
  kind: EntityKind,
  q: string,
  page: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'selector', kind, q, page],
    queryFn: ({ signal }) =>
      read(
        `${kind}?${new URLSearchParams({ q, page: String(page), size: '20' })}`,
        signal,
        entityPageSchema,
      ),
    retry: false,
    gcTime: 0,
  })
type Relations = Pick<AdminSong, 'memberIds' | 'externalArtistIds' | 'workId'>
export async function getSongLabels(
  song: Relations,
  signal: AbortSignal,
  others: Relations[] = [],
) {
  const sources = [song, ...others]
  const works = await Promise.all(
    [...new Set(sources.flatMap((x) => (x.workId ? [x.workId] : [])))].map(
      (id) => read(`works/${id}`, signal, workSchema),
    ),
  )
  const members = [...new Set(sources.flatMap((x) => x.memberIds))]
  const artists = [
    ...new Set([
      ...sources.flatMap((x) => x.externalArtistIds),
      ...works.flatMap((x) => x.artistIds),
    ]),
  ]
  const entries = await Promise.all([
    ...members.map(
      async (id) =>
        [id, (await read(`members/${id}`, signal, namedSchema)).name] as const,
    ),
    ...artists.map(
      async (id) =>
        [id, (await read(`artists/${id}`, signal, namedSchema)).name] as const,
    ),
  ])
  for (const work of works) entries.push([work.id, work.title])
  return {
    names: Object.fromEntries(entries) as Record<string, string>,
    workArtists: works.find((x) => x.id === song.workId)?.artistIds ?? [],
  }
}
export async function saveAdminSong(
  id: string,
  input: SongInput,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(
    `/admin/songs/${encodeURIComponent(id)}`,
    { method: 'PUT', body: input, signal },
  )
  const parsed = z
    .object({ item: songSchema, possibleDuplicateIds: z.array(uuid) })
    .safeParse(data)
  if (
    !parsed.success ||
    parsed.data.item.id !== id ||
    parsed.data.item.version <= input.version
  )
    throw new ApiResponseError()
  return parsed.data
}
export function songEditPath(id: string, returnTo: string) {
  return `/admin/songs/${encodeURIComponent(id)}/edit?${new URLSearchParams({ returnTo })}`
}
export function songReturnPath(value: string | null) {
  if (!value) return '/admin/songs'
  // Only the two list routes may receive the save result or be return destinations.
  const match = /^\/admin\/(songs|registrations)(\?[^#\\]*)?$/.test(value)
  return match ? value : '/admin/songs'
}
