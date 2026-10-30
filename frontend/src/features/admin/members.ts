import { queryOptions } from '@tanstack/react-query'
import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'
import { adminKeys } from './queries'

export type AdminMember =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.Member']
export type MemberInput =
  ApiSchemas['com.stelody.admin.dto.CatalogAdminDtos.MemberInput']
const activity = z.enum(['ACTIVE', 'GRADUATED'])
const version = z.number().int().nonnegative()
export const memberSchema = z
  .object({
    id: z.uuid(),
    version,
    name: z.string(),
    activityStatus: activity,
    generation: z.number().int().min(1).max(32767).nullable(),
    unitName: z.string().nullable(),
    chzzkUrl: z.string().nullable(),
    xUrl: z.string().nullable(),
    profileImageUrl: z.string().nullable(),
    debutDate: z.iso.date().nullable(),
    birthdayMonth: z.number().int().min(1).max(12).nullable(),
    birthdayDay: z.number().int().min(1).max(31).nullable(),
    aliases: z.array(z.string()),
  })
  .refine(
    (m) =>
      (m.birthdayMonth === null && m.birthdayDay === null) ||
      (m.birthdayMonth !== null &&
        m.birthdayDay !== null &&
        validBirthday(m.birthdayMonth, m.birthdayDay)),
  ) satisfies z.ZodType<AdminMember>
const listSchema = z.object({
  page: z.number().int().nonnegative(),
  size: z.number().int().min(1).max(50),
  hasNext: z.boolean(),
  items: z.array(
    z.object({ id: z.uuid(), name: z.string(), version, status: activity }),
  ),
})
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
  const result = schema.safeParse(data)
  if (!result.success) throw new ApiResponseError()
  return result.data
}
export const memberListOptions = (
  user: string,
  q: string,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'members', q, page, size],
    queryFn: async ({ signal }) => {
      const result = await read(
        `members?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
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
export async function getAdminMember(id: string, signal: AbortSignal) {
  const member = await read(
    `members/${encodeURIComponent(id)}`,
    memberSchema,
    signal,
  )
  if (member.id !== id) throw new ApiResponseError()
  return member
}
export const adminMemberOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'member', id],
    queryFn: ({ signal }) => getAdminMember(id, signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
export type MemberDraft = {
  name: string
  unitName: string
  generation: string
  activityStatus: 'ACTIVE' | 'GRADUATED'
  profileImageUrl: string
  chzzkUrl: string
  xUrl: string
  birthday: string
  debutDate: string
  aliases: string[]
}
export const emptyMemberDraft = (): MemberDraft => ({
  name: '',
  unitName: '',
  generation: '',
  activityStatus: 'ACTIVE',
  profileImageUrl: '',
  chzzkUrl: '',
  xUrl: '',
  birthday: '',
  debutDate: '',
  aliases: [],
})
export function memberDraft(m: AdminMember): MemberDraft {
  return {
    name: m.name,
    unitName: m.unitName ?? '',
    generation: m.generation?.toString() ?? '',
    activityStatus: m.activityStatus,
    profileImageUrl: m.profileImageUrl ?? '',
    chzzkUrl: m.chzzkUrl ?? '',
    xUrl: m.xUrl ?? '',
    birthday:
      m.birthdayMonth !== null && m.birthdayDay !== null
        ? `${m.birthdayMonth} / ${m.birthdayDay}`
        : '',
    debutDate: m.debutDate ?? '',
    aliases: [...m.aliases],
  }
}
export function validBirthday(month: number, day: number) {
  const date = new Date(Date.UTC(2000, month - 1, day))
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}
const birthdayParts = (value: string) =>
  value.trim().match(/^(\d{1,2})\s*\/\s*(\d{1,2})$/)
export function memberInput(
  d: MemberDraft,
  version: number,
  reason: string,
): MemberInput {
  const birthday = birthdayParts(d.birthday)
  return {
    version,
    name: d.name.trim(),
    activityStatus: d.activityStatus,
    aliases: d.aliases.map((x) => x.trim()),
    unitName: d.unitName.trim() || null,
    chzzkUrl: d.chzzkUrl.trim() || null,
    xUrl: d.xUrl.trim() || null,
    ...(d.generation.trim() ? { generation: Number(d.generation) } : {}),
    ...(d.profileImageUrl.trim()
      ? { profileImageUrl: d.profileImageUrl.trim() }
      : {}),
    ...(d.debutDate ? { debutDate: d.debutDate } : {}),
    ...(birthday
      ? { birthdayMonth: Number(birthday[1]), birthdayDay: Number(birthday[2]) }
      : {}),
    reason: reason.trim(),
  }
}
export function validMemberUrl(value: string) {
  try {
    const url = new URL(value)
    const host = url.hostname.toLowerCase()
    return (
      url.protocol === 'https:' &&
      !url.username &&
      !url.password &&
      !url.port &&
      !url.hash &&
      !Array.from(value).some(
        (char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127,
      ) &&
      host.includes('.') &&
      !/^[\d.]+$/.test(host) &&
      !host.includes(':') &&
      host !== 'localhost' &&
      !host.endsWith('.localhost')
    )
  } catch {
    return false
  }
}
export function validateMember(d: MemberDraft, reason: string) {
  const errors: Record<string, string> = {}
  if (!d.name.trim() || d.name.length > 100)
    errors.name = '멤버 이름은 1–100자로 입력해주세요.'
  if (d.unitName.length > 100) errors.unitName = '유닛명은 최대 100자입니다.'
  if (
    d.generation.trim() &&
    (!/^\d+$/.test(d.generation.trim()) ||
      Number(d.generation) < 1 ||
      Number(d.generation) > 32767)
  )
    errors.generation = '기수는 1–32767의 정수로 입력하거나 비워주세요.'
  for (const key of ['profileImageUrl', 'chzzkUrl', 'xUrl'] as const)
    if (
      d[key].trim() &&
      (d[key].length > 2000 || !validMemberUrl(d[key].trim()))
    )
      errors[key] = '유효한 HTTPS URL을 입력하거나 비워주세요.'
  if (d.debutDate && !z.iso.date().safeParse(d.debutDate).success)
    errors.debutDate = '올바른 데뷔일을 입력하거나 비워주세요.'
  if (d.birthday.trim()) {
    const parts = birthdayParts(d.birthday)
    if (!parts || !validBirthday(Number(parts[1]), Number(parts[2])))
      errors.birthday =
        '생일은 실제 존재하는 월 / 일로 입력하거나 모두 비워주세요.'
  }
  if (d.aliases.length > 50) errors.aliases = '별칭은 최대 50개입니다.'
  d.aliases.forEach((x, i) => {
    if (!x.trim() || x.length > 200)
      errors[`aliases[${i}]`] = '별칭은 1–200자로 입력해주세요.'
  })
  if (!reason.trim() || reason.length > 500)
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  return errors
}
const savedSchema = z.object({
  item: memberSchema,
  possibleDuplicateIds: z.array(z.uuid()),
})
export type SavedMember = z.infer<typeof savedSchema>
export async function writeMember(
  id: string | null,
  input: MemberInput,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(
    id ? `/admin/members/${encodeURIComponent(id)}` : '/admin/members',
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
export function memberReturnPath(value: string | null) {
  try {
    const url = new URL(value ?? '/admin/members', 'https://stelody.invalid')
    if (
      url.origin === 'https://stelody.invalid' &&
      url.pathname === '/admin/members'
    )
      return url.pathname + url.search
  } catch {
    /* Invalid destinations fall back to the member list. */
  }
  return '/admin/members'
}
export const memberEditPath = (id: string, returnTo: string) =>
  `/admin/members/${encodeURIComponent(id)}/edit?${new URLSearchParams({ returnTo: memberReturnPath(returnTo) })}`
export const memberFields = [
  ['name', '멤버 이름'],
  ['unitName', '유닛명'],
  ['generation', '기수'],
  ['activityStatus', '활동 상태'],
  ['profileImageUrl', '프로필 이미지 URL'],
  ['chzzkUrl', '치지직 공식 링크'],
  ['xUrl', 'X 공식 링크'],
  ['birthday', '생일'],
  ['debutDate', '데뷔일'],
  ['aliases', '검색 별칭'],
] as const
