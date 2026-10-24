import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export const accountPath = '/account'
export const withdrawalPath = '/account/withdrawal'
type Authorization =
  ApiSchemas['com.stelody.user.controller.WithdrawalController.Authorization']
type AuthorizationInput =
  ApiSchemas['com.stelody.user.controller.WithdrawalController.ReauthenticationInput']
export type WithdrawalConfirmation =
  ApiSchemas['com.stelody.auth.service.ReauthenticationService.Confirmation']
export type WithdrawalReceipt =
  ApiSchemas['com.stelody.user.service.WithdrawalResults.Prepared']
export type WithdrawalResult =
  ApiSchemas['com.stelody.user.service.WithdrawalResults.Result']

export const receiptSchema = z.object({
  id: z.uuid(),
  queryToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  expiresAt: z.iso.datetime({ offset: true }),
}) satisfies z.ZodType<WithdrawalReceipt>
const resultSchema = z
  .object({
    id: z.uuid(),
    status: z.enum(['COMPLETED', 'PROCESSING', 'NOT_COMPLETED', 'UNKNOWN']),
    expiresAt: z.iso.datetime({ offset: true }),
    completedAt: z.iso.datetime({ offset: true }).nullable(),
    code: z.string().nullable(),
    pollAfterSeconds: z.number().int().nullable(),
  })
  .refine(
    (v) =>
      (v.status === 'COMPLETED'
        ? v.completedAt !== null
        : v.completedAt === null) &&
      (v.status === 'PROCESSING'
        ? v.pollAfterSeconds === 3
        : v.pollAfterSeconds === null),
  ) satisfies z.ZodType<WithdrawalResult>

export async function prepareWithdrawal(signal?: AbortSignal) {
  const result = receiptSchema.safeParse(
    await apiClient.request<unknown>('/me/withdrawal-requests', {
      method: 'POST',
      cache: 'no-store',
      ...(signal ? { signal } : {}),
    }),
  )
  if (!result.success || Date.parse(result.data.expiresAt) <= Date.now())
    throw new ApiResponseError()
  return result.data
}

export async function getWithdrawalResult(
  receipt: WithdrawalReceipt,
  signal?: AbortSignal,
) {
  const result = resultSchema.safeParse(
    await apiClient.request<unknown>(`/withdrawal-requests/${receipt.id}`, {
      headers: { 'X-Withdrawal-Token': receipt.queryToken },
      cache: 'no-store',
      ...(signal ? { signal } : {}),
    }),
  )
  if (
    !result.success ||
    result.data.id !== receipt.id ||
    Date.parse(result.data.expiresAt) !== Date.parse(receipt.expiresAt) ||
    Date.parse(result.data.expiresAt) <= Date.now()
  )
    throw new ApiResponseError()
  return result.data
}

const confirmationSchema = z
  .object({
    reauthenticated: z.boolean(),
    expiresAt: z.iso.datetime({ offset: true }).nullable(),
  })
  .refine(
    (v) => v.reauthenticated === (v.expiresAt !== null),
  ) satisfies z.ZodType<WithdrawalConfirmation>
const authorizationSchema = z.object({
  authorizationUrl: z.url(),
  expiresAt: z.iso.datetime({ offset: true }),
}) satisfies z.ZodType<Authorization>

export async function getWithdrawalConfirmation(signal?: AbortSignal) {
  const parsed = confirmationSchema.safeParse(
    await apiClient.request<unknown>(
      '/me/withdrawal-confirmation',
      signal ? { signal } : {},
    ),
  )
  if (!parsed.success) throw new ApiResponseError()
  return parsed.data
}

export function isConfirmed(value: WithdrawalConfirmation, now = Date.now()) {
  return (
    value.reauthenticated &&
    !!value.expiresAt &&
    Date.parse(value.expiresAt) > now
  )
}

export async function startReauthentication(signal?: AbortSignal) {
  const body: AuthorizationInput = {
    returnTo: withdrawalPath,
    callbackClient: import.meta.env.DEV ? 'local' : 'default',
  }
  const parsed = authorizationSchema.safeParse(
    await apiClient.request<unknown>('/me/reauthentications', {
      method: 'POST',
      body,
      ...(signal ? { signal } : {}),
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

export async function withdrawAccount(requestId: string, signal?: AbortSignal) {
  if (!z.uuid().safeParse(requestId).success) throw new ApiResponseError()
  const response = await apiClient.request<unknown>('/me', {
    method: 'DELETE',
    headers: { 'Withdrawal-Request-Id': requestId },
    ...(signal ? { signal } : {}),
  })
  if (response !== undefined) throw new ApiResponseError()
}
