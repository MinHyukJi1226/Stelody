import { z } from 'zod'
import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { adminKeys } from './queries'
const time = z.iso.datetime({ offset: true })
const count = z.number().int().nonnegative()
export const kindSchema = z.enum(['VIDEO', 'DISCOVERY'])
export const retrySchema = z.object({
  id: z.uuid(),
  kind: kindSchema,
  runId: z.uuid(),
  expectedAttempt: count,
  status: z.enum(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED']),
  createdAt: time,
  startedAt: time.nullable(),
  finishedAt: time.nullable(),
  executionRunId: z.uuid().nullable(),
  errorCode: z.string().nullable(),
})
export const runSchema = z.object({
  id: z.uuid(),
  kind: kindSchema,
  mode: z.string().nullable(),
  channelId: z.uuid().nullable(),
  logicalSlot: time.nullable(),
  status: z.string(),
  attempt: count,
  startedAt: time,
  finishedAt: time.nullable(),
  errorCode: z.string().nullable(),
  totalCount: count,
  observedCount: count,
  skippedCount: count,
  pendingCount: count,
  pages: count,
  candidates: count,
  ruleVersion: z.string().nullable(),
})
export type Retry = z.infer<typeof retrySchema>
export type Run = z.infer<typeof runSchema>
export const requestSchema = z.object({
  requestId: z.uuid(),
  attempt: z.number().int().positive(),
  reason: z.string().min(1).max(500),
})
export type RetryInput = z.infer<typeof requestSchema>
export const receiptSchema = z.object({
  kind: kindSchema,
  runId: z.uuid(),
  body: requestSchema,
})
export type Receipt = z.infer<typeof receiptSchema>
async function read<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
) {
  const data = await apiClient.request<unknown>(path, {
    signal,
    cache: 'no-store',
  })
  signal.throwIfAborted()
  const p = schema.safeParse(data)
  if (!p.success) throw new ApiResponseError()
  return p.data
}
export async function getRun(kind: string, id: string, signal: AbortSignal) {
  const v = await read(
    `/admin/collection-runs/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`,
    runSchema,
    signal,
  )
  if (v.id !== id || v.kind !== kind) throw new ApiResponseError()
  return v
}
export const getRetryOverview = (signal: AbortSignal) =>
  read(
    '/admin/collection-status',
    z.object({
      checkedAt: time,
      retryEnabled: z.boolean(),
      activeRetry: retrySchema.nullable(),
    }),
    signal,
  )
export const retryRunOptions = (
  user: string,
  kind: string,
  id: string,
  retryId: string | null = null,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'retry-run', kind, id, retryId],
    queryFn: async ({ signal }) => ({
      run: await getRun(kind, id, signal),
      overview: await getRetryOverview(signal),
      retry: retryId
        ? await readExistingRetry(kind, id, retryId, signal)
        : null,
    }),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
export async function getRetry(receipt: Receipt, signal: AbortSignal) {
  const v = await read(
    `/admin/collection-retries/${receipt.body.requestId}`,
    retrySchema,
    signal,
  )
  if (
    v.kind !== receipt.kind ||
    v.runId !== receipt.runId ||
    v.id !== receipt.body.requestId ||
    v.expectedAttempt !== receipt.body.attempt
  )
    throw new ApiResponseError()
  return v
}
export async function submitRetry(receipt: Receipt, signal: AbortSignal) {
  const data = await apiClient.request<unknown>(
    `/admin/collection-runs/${receipt.kind}/${receipt.runId}/retries`,
    { method: 'POST', body: receipt.body, signal },
  )
  signal.throwIfAborted()
  const v = retrySchema.parse(data)
  if (
    v.kind !== receipt.kind ||
    v.runId !== receipt.runId ||
    v.id !== receipt.body.requestId ||
    v.expectedAttempt !== receipt.body.attempt
  )
    throw new ApiResponseError()
  return v
}
export function retryUnavailable(run: Run, now: string) {
  if (!['FAILED', 'QUOTA_EXHAUSTED', 'TIMED_OUT'].includes(run.status))
    return '작업의 최신 상태를 확인해 주세요'
  const age = run.kind === 'VIDEO' ? run.logicalSlot : run.startedAt
  if (
    !age ||
    Date.parse(now) - Date.parse(age) > 172800000 ||
    Date.parse(age) > Date.parse(now)
  )
    return '재시도 가능한 기간이 지났습니다'
  return null
}
export function failureReturnPath(value: string | null) {
  try {
    const u = new URL(value ?? '/admin/failures', 'https://stelody.invalid')
    if (
      u.origin === 'https://stelody.invalid' &&
      u.pathname === '/admin/failures'
    )
      return u.pathname + u.search
  } catch {
    /* safe fallback */
  }
  return '/admin/failures'
}
export const runPath = (kind: string, id: string, returnTo: string) =>
  `/admin/collection-runs/${encodeURIComponent(kind)}/${encodeURIComponent(id)}?${new URLSearchParams({ returnTo: failureReturnPath(returnTo) })}`
export const receiptKey = (user: string, kind: string, id: string) =>
  `stelody:collection-retry:${user}:${kind}:${id}`
export function readReceipt(key: string): Receipt | null {
  const raw = sessionStorage.getItem(key)
  return raw ? receiptSchema.parse(JSON.parse(raw)) : null
}
export function storeReceipt(key: string, value: Receipt) {
  const raw = JSON.stringify(value)
  sessionStorage.setItem(key, raw)
  if (sessionStorage.getItem(key) !== raw)
    throw new Error('STORAGE_UNAVAILABLE')
}
export const retryErrorLabels: Record<string, string> = {
  COLLECTION_RETRY_DISABLED: '지금은 재시도를 사용할 수 없습니다',
  COLLECTION_RETRY_EXPIRED: '재시도 가능한 기간이 지났습니다',
  COLLECTION_RUN_CHANGED: '작업의 최신 상태를 확인해 주세요',
  COLLECTION_RUN_NOT_RETRYABLE: '작업의 최신 상태를 확인해 주세요',
  COLLECTION_RETRY_ALREADY_ACTIVE: '이미 진행 중인 재시도가 있습니다',
  COLLECTION_RETRY_KEY_REUSED: '요청 ID가 다른 작업에 사용되었습니다',
}

export async function readExistingRetry(
  kind: string,
  runId: string,
  id: string,
  signal: AbortSignal,
) {
  const v = await read(
    `/admin/collection-retries/${encodeURIComponent(id)}`,
    retrySchema,
    signal,
  )
  if (v.id !== id || v.kind !== kind || v.runId !== runId)
    throw new ApiResponseError()
  return v
}
