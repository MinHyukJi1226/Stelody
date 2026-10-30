import { queryOptions } from '@tanstack/react-query'
import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'
import { adminKeys } from './queries'

export type AdminChannel =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Channel']
export type ChannelInput =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.ChannelInput']
const type = z.enum(['GROUP', 'MEMBER', 'EXTERNAL'])
const version = z.number().int().nonnegative()
const schema = z
  .object({
    id: z.uuid(),
    version,
    youtubeId: z.string().regex(/^UC[A-Za-z0-9_-]{22}$/),
    name: z.string(),
    memberId: z.uuid().nullable(),
    channelType: type,
    collectionEnabled: z.boolean(),
  })
  .refine(
    (c) =>
      (c.channelType === 'MEMBER' || c.memberId === null) &&
      (c.channelType !== 'EXTERNAL' || !c.collectionEnabled),
  ) satisfies z.ZodType<AdminChannel>
const listSchema = z.object({
  page: z.number().int().nonnegative(),
  size: z.number().int().min(1).max(50),
  hasNext: z.boolean(),
  items: z.array(
    z.object({ id: z.uuid(), name: z.string(), version, status: type }),
  ),
})
async function read<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
) {
  const result = await apiClient.request<unknown>(`/admin/${path}`, {
    signal,
    cache: 'no-store',
  })
  signal.throwIfAborted()
  const parsed = schema.safeParse(result)
  if (!parsed.success) throw new ApiResponseError()
  return parsed.data
}
export const channelListOptions = (
  user: string,
  q: string,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'channels', q, page, size],
    queryFn: async ({ signal }) => {
      const result = await read(
        `channels?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
        listSchema,
        signal,
      )
      if (result.page !== page || result.size !== size)
        throw new ApiResponseError()
      return result
    },
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
export async function getAdminChannel(id: string, signal: AbortSignal) {
  const result = await read(
    `channels/${encodeURIComponent(id)}`,
    schema,
    signal,
  )
  if (result.id !== id) throw new ApiResponseError()
  return result
}
export const adminChannelOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'channel', id],
    queryFn: ({ signal }) => getAdminChannel(id, signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
export type ChannelDraft = {
  youtubeId: string
  name: string
  channelType: 'GROUP' | 'MEMBER' | 'EXTERNAL'
  memberId: string
  collectionEnabled: boolean
}
export const emptyChannelDraft = (): ChannelDraft => ({
  youtubeId: '',
  name: '',
  channelType: 'GROUP',
  memberId: '',
  collectionEnabled: false,
})
export const channelDraft = (c: AdminChannel): ChannelDraft => ({
  youtubeId: c.youtubeId,
  name: c.name,
  channelType: c.channelType as ChannelDraft['channelType'],
  memberId: c.memberId ?? '',
  collectionEnabled: c.collectionEnabled,
})
export const channelInput = (
  d: ChannelDraft,
  version: number,
  reason: string,
): ChannelInput => ({
  version,
  youtubeId: d.youtubeId.trim(),
  name: d.name.trim(),
  channelType: d.channelType,
  ...(d.memberId ? { memberId: d.memberId } : {}),
  collectionEnabled: d.collectionEnabled,
  reason: reason.trim(),
})
export function validateChannel(d: ChannelDraft, reason: string) {
  const errors: Record<string, string> = {}
  if (!/^UC[A-Za-z0-9_-]{22}$/.test(d.youtubeId.trim()))
    errors.youtubeId = 'UC로 시작하는 24자 YouTube 채널 ID를 입력해주세요.'
  if (!d.name.trim() || d.name.length > 200)
    errors.name = '채널명은 1–200자로 입력해주세요.'
  if (
    d.memberId &&
    (d.channelType !== 'MEMBER' || !z.uuid().safeParse(d.memberId).success)
  )
    errors.memberId =
      '연결 멤버를 확인해주세요. 멤버 채널만 연결할 수 있습니다.'
  if (d.channelType === 'EXTERNAL' && d.collectionEnabled)
    errors.collectionEnabled = '외부 채널은 수집을 사용할 수 없습니다.'
  if (!reason.trim() || reason.length > 500)
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  return errors
}
export type SavedChannel = { item: AdminChannel }
export async function writeChannel(
  id: string | null,
  input: ChannelInput,
  signal: AbortSignal,
): Promise<SavedChannel> {
  const data = await apiClient.request<unknown>(
    id ? `/admin/channels/${encodeURIComponent(id)}` : '/admin/channels',
    { method: id ? 'PUT' : 'POST', body: input, signal },
  )
  signal.throwIfAborted()
  const result = schema.safeParse(data)
  if (
    !result.success ||
    (id && (result.data.id !== id || result.data.version <= input.version))
  )
    throw new ApiResponseError()
  return { item: result.data }
}
export function channelReturnPath(value: string | null) {
  try {
    const url = new URL(value ?? '/admin/channels', 'https://stelody.invalid')
    if (
      url.origin === 'https://stelody.invalid' &&
      url.pathname === '/admin/channels'
    )
      return url.pathname + url.search
  } catch {
    /* Fall back to the channel list. */
  }
  return '/admin/channels'
}
export const channelEditPath = (id: string, returnTo: string) =>
  `/admin/channels/${encodeURIComponent(id)}/edit?${new URLSearchParams({ returnTo: channelReturnPath(returnTo) })}`
export const channelLabels = { GROUP: '그룹', MEMBER: '멤버', EXTERNAL: '외부' }
export const channelFields = [
  ['youtubeId', 'YouTube 채널 ID'],
  ['name', '채널명'],
  ['channelType', '채널 유형'],
  ['memberId', '연결 멤버 ID'],
  ['collectionEnabled', '수집 사용 여부'],
] as const
