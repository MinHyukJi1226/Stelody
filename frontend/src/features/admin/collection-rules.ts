import { queryOptions } from '@tanstack/react-query'
import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { adminKeys } from './queries'
const marker = z.object({ text: z.string(), match: z.enum(['WORD', 'PHRASE']) })
export const configurationSchema = z.object({
  cover: z.array(marker).max(30),
  original: z.array(marker).max(30),
  exclude: z.array(marker).max(30),
})
const snapshotSchema = z.object({
  version: z.number().int().nonnegative(),
  configuration: configurationSchema,
})
export type RuleSnapshot = z.infer<typeof snapshotSchema>
export type Configuration = z.infer<typeof configurationSchema>
export const ruleGroups = [
  ['cover', '커버 규칙'],
  ['original', '오리지널 규칙'],
  ['exclude', '제외 규칙'],
] as const
export const availability = [
  'PUBLIC',
  'UNLISTED',
  'PRIVATE',
  'DELETED',
  'UNAVAILABLE',
] as const
export type Sample = {
  title: string
  availability: (typeof availability)[number]
}
const resultSchema = z.object({
  baseVersion: z.number().int().nonnegative(),
  decisions: z.array(
    z.object({
      disposition: z.enum(['REVIEW', 'EXCLUDED', 'DEFERRED']),
      suggestedType: z.enum(['COVER', 'ORIGINAL', 'UNKNOWN']),
      reason: z.string(),
      version: z.string(),
    }),
  ),
})
export type PreviewResult = z.infer<typeof resultSchema>
function parse<T>(schema: z.ZodType<T>, data: unknown) {
  const p = schema.safeParse(data)
  if (!p.success) throw new ApiResponseError()
  return p.data
}
export async function getRules(signal: AbortSignal) {
  const data = await apiClient.request<unknown>('/admin/collection-rules', {
    signal,
    cache: 'no-store',
  })
  signal.throwIfAborted()
  return parse(snapshotSchema, data)
}
export const rulesOptions = (user: string) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'collection-rules'],
    queryFn: ({ signal }) => getRules(signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
export async function writeRules(
  input: RuleSnapshot & { reason: string },
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>('/admin/collection-rules', {
    method: 'PUT',
    body: input,
    signal,
  })
  signal.throwIfAborted()
  const p = parse(snapshotSchema, data)
  if (p.version <= input.version) throw new ApiResponseError()
  return p
}
export async function previewRules(
  input: RuleSnapshot & { samples: Sample[] },
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(
    '/admin/collection-rules/preview',
    { method: 'POST', body: input, signal },
  )
  signal.throwIfAborted()
  const p = parse(resultSchema, data)
  if (
    p.baseVersion !== input.version ||
    p.decisions.length !== input.samples.length
  )
    throw new ApiResponseError()
  return p
}
const normalize = (s: string) =>
  s
    .normalize('NFKC')
    .replace(/[\s\p{Z}]+/gu, ' ')
    .trim()
    .toLowerCase()
export function validateRules(d: Configuration, reason?: string) {
  const errors: Record<string, string> = {}
  for (const [key] of ruleGroups) {
    if (d[key].length > 30)
      errors[`configuration.${key}`] = '규칙은 종류별 최대 30개입니다.'
    const seen = new Set<string>()
    d[key].forEach((m, i) => {
      const text = normalize(m.text),
        p = `configuration.${key}[${i}]`
      if (!text || m.text.length > 60 || text.length > 60)
        errors[p + '.text'] = '단어·문구는 1–60자로 입력해주세요.'
      else if (seen.has(text))
        errors[p + '.text'] = '같은 종류에 중복된 단어·문구가 있습니다.'
      seen.add(text)
    })
  }
  if (reason !== undefined && (!reason.trim() || reason.length > 500))
    errors.reason = '변경 사유는 1–500자로 입력해주세요.'
  return errors
}
export function validateSamples(samples: Sample[]) {
  const errors: Record<string, string> = {}
  if (samples.length < 1 || samples.length > 20)
    errors.samples = '샘플을 1–20개 입력해주세요.'
  samples.forEach((s, i) => {
    if (!s.title.trim() || s.title.length > 500)
      errors[`samples[${i}].title`] = '샘플 제목은 1–500자로 입력해주세요.'
  })
  return errors
}
