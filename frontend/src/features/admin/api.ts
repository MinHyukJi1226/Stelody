import { apiClient } from '@/shared/api/client'
import { z } from 'zod'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas, CurrentUser } from '@/shared/api/types'

export type Inbox = ApiSchemas['com.stelody.admin.dto.InboxDtos.Overview']
export type Collection =
  ApiSchemas['com.stelody.collection.dto.CollectionDtos.Overview']
export type Health =
  ApiSchemas['com.stelody.collection.dto.CollectionDtos.Health']
export type Run = ApiSchemas['com.stelody.collection.dto.CollectionDtos.Run']
export type Reviews = ApiSchemas['com.stelody.review.dto.ReviewDtos.Page']
const registrationsSchema = z.object({
  items: z.array(
    z.object({
      reviewId: z.uuid(),
      title: z.string(),
      visibility: z.string(),
      processedAt: z.iso.datetime({ offset: true }),
      missingFields: z.array(z.string()),
    }),
  ),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  hasNext: z.boolean(),
}) satisfies z.ZodType<
  ApiSchemas['com.stelody.admin.dto.CoverPublicationDtos.Page']
>
export type Registrations = z.infer<typeof registrationsSchema>
export type SpecialReviews =
  ApiSchemas['com.stelody.admin.dto.SpecialReviewDtos.Page']
export type Failures =
  ApiSchemas['com.stelody.collection.dto.CollectionDtos.FailurePage']
export type FailureWindow = { from: string; to: string }

const instant = z.iso.datetime({ offset: true })
const count = z.number().int().nonnegative()
const inboxSchema = z.object({
  checkedAt: instant,
  failureWindowStart: instant,
  newReviewCount: count,
  incompleteAutoRegistrationCount: count,
  specialEventReviewCount: count,
  collectionFailureCount: count,
}) satisfies z.ZodType<Inbox>
const runSchema = z.object({
  id: z.uuid(),
  kind: z.string(),
  status: z.string(),
  attempt: count,
  startedAt: instant,
  finishedAt: instant.nullable(),
  errorCode: z.string().nullable(),
})
const healthSchema = z.object({
  latest: runSchema.nullable(),
  lastSuccessAt: instant.nullable(),
  lastFailureAt: instant.nullable(),
  missedSlots: count,
  delayed: z.boolean(),
  runningOverdue: z.boolean(),
})
const collectionSchema = z.object({
  checkedAt: instant,
  video: healthSchema,
  discovery: healthSchema,
  pendingReviews: count,
  deferredReviews: count,
})
const pagination = {
  page: count,
  size: z.number().int().positive(),
  hasNext: z.boolean(),
}
const reviewsSchema = z.object({
  ...pagination,
  items: z.array(
    z.object({
      id: z.uuid(),
      title: z.string().nullable(),
      channelName: z.string().nullable(),
      firstSeenAt: instant,
      availability: z.string(),
    }),
  ),
})
const specialSchema = z.object({
  page: count,
  size: z.number().int().positive(),
  hasMore: z.boolean(),
  items: z.array(
    z.object({
      id: z.uuid(),
      title: z.string(),
      isSpecialEvent: z.boolean(),
      specialEventLabel: z.string().nullable(),
      basisCurrent: z.boolean(),
      evidence: z.array(
        z.object({
          memberName: z.string(),
          reason: z.string(),
          publishedDate: z.iso.date(),
        }),
      ),
      createdAt: instant,
    }),
  ),
})
const failuresSchema = z.object({
  ...pagination,
  from: instant,
  to: instant,
  items: z.array(runSchema),
})

export const isActiveAdmin = (user: CurrentUser | null | undefined) =>
  user?.role === 'ADMIN' && user.status === 'ACTIVE'

async function read<T>(path: string, signal: AbortSignal, schema: z.ZodType) {
  const data = await apiClient.request<unknown>(`/admin/${path}`, {
    signal,
    cache: 'no-store',
  })
  // Validate displayed fields while preserving the rest of the OpenAPI response.
  if (!schema.safeParse(data).success) throw new ApiResponseError()
  return data as T
}
export const getInbox = (signal: AbortSignal) =>
  read<Inbox>('inbox', signal, inboxSchema)
export const getCollection = (signal: AbortSignal) =>
  read<Collection>('collection-status', signal, collectionSchema)

function pageQuery(page: number) {
  return new URLSearchParams({ page: String(page), size: '20' })
}
export function getReviews(page: number, signal: AbortSignal) {
  const query = pageQuery(page)
  query.set('status', 'PENDING')
  query.set('disposition', 'REVIEW')
  return read<Reviews>(`reviews?${query}`, signal, reviewsSchema)
}
export function getRegistrations(page: number, signal: AbortSignal) {
  const query = pageQuery(page)
  query.set('incompleteOnly', 'true')
  return read<Registrations>(
    `cover-auto-publication/registrations?${query}`,
    signal,
    registrationsSchema,
  )
}
export function getSpecialReviews(page: number, signal: AbortSignal) {
  const query = pageQuery(page)
  query.set('status', 'PENDING')
  return read<SpecialReviews>(
    `special-event-reviews?${query}`,
    signal,
    specialSchema,
  )
}
export function getFailures(
  page: number,
  window: FailureWindow,
  signal: AbortSignal,
) {
  const query = pageQuery(page)
  query.set('from', window.from)
  query.set('to', window.to)
  return read<Failures>(`collection-failures?${query}`, signal, failuresSchema)
}

export const adminPaths = {
  inbox: '/admin',
  collection: '/admin/collection',
  reviews: '/admin/reviews',
  registrations: '/admin/registrations',
  special: '/admin/special-event-reviews',
  failures: '/admin/failures',
}
export function failurePath(window: FailureWindow) {
  return `${adminPaths.failures}?${new URLSearchParams(window)}`
}
