import { queryOptions } from '@tanstack/react-query'
import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { adminKeys } from './queries'
import {
  getWork,
  workSchema,
  type Work,
  type WorkInput,
} from './original-artists'

const pageSchema = z.object({
  page: z.number().int().nonnegative(),
  size: z.number().int().min(1).max(50),
  hasNext: z.boolean(),
  items: z.array(
    z.object({
      id: z.uuid(),
      name: z.string(),
      version: z.number().int().nonnegative(),
      status: z.string().nullable(),
    }),
  ),
})
export const workListOptions = (
  user: string,
  q: string,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'works', q, page, size],
    queryFn: async ({ signal }) => {
      const data = await apiClient.request<unknown>(
        `/admin/works?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
        { signal, cache: 'no-store' },
      )
      signal.throwIfAborted()
      const result = pageSchema.safeParse(data)
      if (
        !result.success ||
        result.data.page !== page ||
        result.data.size !== size
      )
        throw new ApiResponseError()
      return result.data
    },
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
export const workOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'work', id],
    queryFn: ({ signal }) => getWork(id, signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
export type WorkDraft = {
  title: string
  aliases: string[]
  artistIds: string[]
  links: WorkInput['links']
  karaoke: {
    provider: 'TJ' | 'KY'
    status: 'UNKNOWN' | 'NOT_LISTED' | 'REGISTERED'
    number: string
    sourceUrl: string
  }[]
}
export const emptyWorkDraft = (): WorkDraft => ({
  title: '',
  aliases: [],
  artistIds: [],
  links: [],
  karaoke: (['TJ', 'KY'] as const).map((provider) => ({
    provider,
    status: 'UNKNOWN',
    number: '',
    sourceUrl: '',
  })),
})
export function toWorkDraft(work: Work): WorkDraft {
  return {
    title: work.title,
    aliases: [...work.aliases],
    artistIds: [...work.artistIds],
    links: work.links.map(({ platform, url, sourceUrl }) => ({
      platform,
      url,
      sourceUrl,
    })),
    karaoke: emptyWorkDraft().karaoke.map((k) => {
      const saved = work.karaoke.find((x) => x.provider === k.provider)
      return saved
        ? {
            ...k,
            status: saved.status,
            number: saved.number ?? '',
            sourceUrl: saved.sourceUrl ?? '',
          }
        : k
    }),
  }
}
export function workInput(
  draft: WorkDraft,
  version: number,
  reason: string,
  base?: Work | null,
): WorkInput {
  return {
    version,
    title: draft.title.trim(),
    aliases: draft.aliases.map((x) => x.trim()),
    artistIds: [...draft.artistIds],
    links: draft.links.map((x) => ({
      platform: x.platform.trim(),
      url: x.url.trim(),
      sourceUrl: x.sourceUrl.trim(),
    })),
    karaoke: draft.karaoke
      .filter(
        (x) =>
          !base ||
          base.karaoke.some((k) => k.provider === x.provider) ||
          x.status !== 'UNKNOWN' ||
          !!x.sourceUrl.trim(),
      )
      .map((x) => ({
        provider: x.provider,
        status: x.status,
        ...(x.status === 'REGISTERED' ? { number: x.number.trim() } : {}),
        ...(x.sourceUrl.trim() ? { sourceUrl: x.sourceUrl.trim() } : {}),
      })),
    reason: reason.trim(),
  }
}
export const workFields = [
  ['title', '원곡 제목'],
  ['aliases', '검색 별칭'],
  ['artistIds', '원곡 아티스트'],
  ['links', '링크'],
  ['karaoke', 'TJ/KY'],
] as const
export type WorkField = (typeof workFields)[number][0]
export function validateWork(d: WorkDraft, reason: string) {
  const errors: Record<string, string> = {}
  if (!d.title.trim() || d.title.length > 300)
    errors.title = '원곡 제목은 1–300자로 입력해주세요.'
  if (!reason.trim() || reason.length > 500)
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  if (d.aliases.length > 50) errors.aliases = '별칭은 최대 50개입니다.'
  d.aliases.forEach((x, i) => {
    if (!x.trim() || x.length > 300)
      errors[`aliases[${i}]`] = '별칭은 1–300자로 입력해주세요.'
  })
  if (d.artistIds.length > 100)
    errors.artistIds = '아티스트는 최대 100명입니다.'
  if (d.links.length > 20) errors.links = '링크는 최대 20개입니다.'
  const https = (value: string) => {
    try {
      const url = new URL(value)
      return (
        url.protocol === 'https:' &&
        !!url.hostname &&
        !url.username &&
        !url.password
      )
    } catch {
      return false
    }
  }
  d.links.forEach((x, i) => {
    if (!x.platform.trim() || x.platform.length > 60)
      errors[`links[${i}].platform`] = '플랫폼은 1–60자로 입력해주세요.'
    for (const key of ['url', 'sourceUrl'] as const)
      if (x[key].length > 2000 || !https(x[key].trim()))
        errors[`links[${i}].${key}`] = 'HTTPS URL을 입력해주세요.'
  })
  d.karaoke.forEach((x, i) => {
    if (x.status === 'REGISTERED' && (!x.number.trim() || x.number.length > 40))
      errors[`karaoke[${i}].number`] = '노래방 번호는 1–40자로 입력해주세요.'
    if (
      (x.status !== 'UNKNOWN' || x.sourceUrl.trim()) &&
      (x.sourceUrl.length > 2000 || !https(x.sourceUrl.trim()))
    )
      errors[`karaoke[${i}].sourceUrl`] =
        '확인 근거의 HTTPS URL을 입력해주세요.'
  })
  return errors
}
const savedSchema = z.object({
  item: workSchema,
  possibleDuplicateIds: z.array(z.uuid()),
})
export type SavedWork = z.infer<typeof savedSchema>
export async function writeWork(
  id: string | null,
  input: WorkInput,
  signal: AbortSignal,
): Promise<SavedWork> {
  const data = await apiClient.request<unknown>(
    id ? `/admin/works/${encodeURIComponent(id)}` : '/admin/works',
    { method: id ? 'PUT' : 'POST', body: input, signal },
  )
  signal.throwIfAborted()
  const result = savedSchema.safeParse(data)
  if (
    !result.success ||
    (id &&
      (result.data.item.id !== id || result.data.item.version <= input.version))
  )
    throw new ApiResponseError()
  return result.data
}
export function workReturnPath(value: string | null) {
  try {
    const url = new URL(value ?? '/admin/works', 'https://stelody.invalid')
    if (
      url.origin === 'https://stelody.invalid' &&
      url.pathname === '/admin/works'
    )
      return url.pathname + url.search
  } catch {
    /* An invalid destination returns to the work list. */
  }
  return '/admin/works'
}
export const workEditPath = (id: string, returnTo: string) =>
  `/admin/works/${encodeURIComponent(id)}/edit?${new URLSearchParams({ returnTo: workReturnPath(returnTo) })}`
