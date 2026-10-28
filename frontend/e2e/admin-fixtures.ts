import type { Page } from '@playwright/test'
import type { Collection, Inbox, Run } from '../src/features/admin/api'
import { makeSong } from './fixtures'

export const admin = {
  id: makeSong(991).id,
  email: 'admin@example.com',
  role: 'ADMIN',
  status: 'ACTIVE',
}
export const inbox: Inbox = {
  checkedAt: '2026-10-24T03:05:00Z',
  failureWindowStart: '2026-10-23T03:05:00Z',
  newReviewCount: 8,
  incompleteAutoRegistrationCount: 12,
  specialEventReviewCount: 3,
  collectionFailureCount: 4,
}
export const run: Run = {
  id: makeSong(992).id,
  kind: 'VIDEO',
  mode: null,
  channelId: null,
  logicalSlot: null,
  status: 'QUOTA_EXHAUSTED',
  attempt: 2,
  startedAt: '2026-10-24T03:00:00Z',
  finishedAt: null,
  errorCode: 'QUOTA_EXHAUSTED',
  totalCount: 12,
  observedCount: 10,
  skippedCount: 0,
  pendingCount: 2,
  pages: 0,
  candidates: 0,
  ruleVersion: null,
}
const health = {
  latest: { ...run, status: 'SUCCEEDED' },
  lastSuccessAt: '2026-10-24T03:02:00Z',
  lastFailureAt: '2026-10-23T03:03:00Z',
  missedSlots: 0,
  delayed: false,
  runningOverdue: false,
}
export const collection: Collection = {
  checkedAt: inbox.checkedAt,
  retryEnabled: false,
  video: health,
  discovery: { ...health, lastSuccessAt: '2026-10-23T15:12:00Z' },
  pendingReviews: 8,
  deferredReviews: 2,
  activeRetry: null,
}

export async function mockAdmin(page: Page) {
  const state = {
    role: 'ADMIN',
    status: 'ACTIVE',
    loggedIn: true,
    sessionError: 0,
    error: 0,
    errorPage: -1,
    empty: false,
    nullHealth: false,
    zeroCounts: false,
    malformedPath: '',
    sessionGate: Promise.resolve(),
    listGate: Promise.resolve(),
    calls: [] as URL[],
    methods: [] as string[],
  }
  await page.route('**/api/v1/me', async (route) => {
    await state.sessionGate
    await route.fulfill(
      state.sessionError
        ? {
            status: state.sessionError,
            json: { code: 'LOGIN_STORAGE_UNAVAILABLE' },
          }
        : state.loggedIn
          ? { json: { ...admin, role: state.role, status: state.status } }
          : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } },
    )
  })
  await page.route('**/api/v1/admin/**', async (route) => {
    const url = new URL(route.request().url())
    state.calls.push(url)
    state.methods.push(route.request().method())
    await state.listGate
    const number = Number(url.searchParams.get('page') ?? '0')
    if (state.error || number === state.errorPage)
      return route.fulfill({
        status: state.error || 503,
        json: {
          code:
            state.error === 403
              ? 'ACCESS_DENIED'
              : state.error === 401
                ? 'SESSION_EXPIRED'
                : 'ADMIN_STORAGE_UNAVAILABLE',
        },
      })
    const path = url.pathname.split('/').at(-1)
    if (path === state.malformedPath)
      return route.fulfill({ json: { items: [], totalCount: 0 } })
    if (path === 'inbox')
      return route.fulfill({
        json: state.zeroCounts
          ? {
              ...inbox,
              newReviewCount: 0,
              incompleteAutoRegistrationCount: 0,
              specialEventReviewCount: 0,
              collectionFailureCount: 0,
            }
          : inbox,
      })
    if (path === 'collection-status')
      return route.fulfill({
        json: state.nullHealth
          ? {
              ...collection,
              video: {
                ...health,
                latest: null,
                lastSuccessAt: null,
                lastFailureAt: null,
                delayed: true,
                runningOverdue: true,
                missedSlots: 2,
              },
            }
          : collection,
      })
    if (path === 'completion')
      return route.fulfill({
        json: {
          items: state.empty
            ? []
            : [
                {
                  id: makeSong(993).id,
                  version: 1,
                  youtubeId: 'candidate',
                  title: '신규 후보 1',
                  channelId: null,
                  channelName: '아야츠노 유니',
                  publishedAt: null,
                  thumbnailUrl: null,
                  durationSeconds: null,
                  observedAt: null,
                  availability: 'PUBLIC',
                  disposition: 'REVIEW',
                  suggestedType: 'COVER',
                  ruleVersion: '1',
                  decisionReason: '검토 필요',
                  status: 'PENDING',
                  reviewNote: null,
                  firstSeenAt: '2026-10-23T15:05:00Z',
                  sourceExpired: false,
                  registeredVideoId: null,
                },
              ],
          hasMore: false,
          channelMembers: {},
        },
      })
    const paged = {
      page: number,
      size: 20,
      hasNext: number === 0,
      items: [] as unknown[],
    }
    if (path === 'reviews')
      paged.items = [
        {
          id: makeSong(993 + number).id,
          title: `신규 후보 ${number + 1}`,
          channelName: '아야츠노 유니',
          firstSeenAt: '2026-10-23T15:05:00Z',
          availability: 'PUBLIC',
        },
      ]
    if (path === 'registrations')
      paged.items = [
        {
          reviewId: makeSong(993 + number).id,
          songId: makeSong(993 + number).id,
          title: `정보 보완 곡 ${number + 1}`,
          visibility: 'HIDDEN',
          processedAt: '2026-10-23T15:08:00Z',
          missingFields: ['work', 'aliases', 'TJ'],
        },
      ]
    if (path === 'special-event-reviews')
      paged.items = [
        {
          id: makeSong(993 + number).id,
          title: `특별 후보 ${number + 1}`,
          isSpecialEvent: false,
          specialEventLabel: null,
          basisCurrent: false,
          evidence:
            number === 0
              ? [
                  {
                    memberName: '아야츠노 유니',
                    reason: 'BIRTHDAY_DATE_MATCH',
                    publishedDate: '2026-10-20',
                  },
                ]
              : [],
          createdAt: '2026-10-20T15:10:00Z',
        },
      ]
    if (path === 'collection-failures')
      paged.items = [
        {
          ...run,
          id: makeSong(993 + number).id,
          kind: number === 0 ? 'VIDEO' : 'DISCOVERY',
        },
      ]
    if (state.empty) paged.items = []
    await route.fulfill({
      json:
        path === 'special-event-reviews'
          ? { ...paged, hasNext: undefined, hasMore: number === 0 }
          : path === 'collection-failures'
            ? {
                ...paged,
                from: url.searchParams.get('from'),
                to: url.searchParams.get('to'),
              }
            : paged,
    })
  })
  return state
}
