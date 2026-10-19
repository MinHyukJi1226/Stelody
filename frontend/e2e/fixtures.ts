import { test as base, expect } from '@playwright/test'
import type { Song } from '../src/features/songs/api'

export const emptyPage = {
  items: [],
  nextCursor: null,
  hasNext: false,
  totalCount: 0,
}

export function makeSong(index: number, overrides: Partial<Song> = {}): Song {
  return {
    id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    title: `테스트 노래 ${index}`,
    type: 'COVER',
    publishedAt: '2026-10-14T16:30:00Z',
    thumbnailUrl: null,
    participants: [
      {
        id: '00000000-0000-4000-8000-000000000099',
        name: '테스트 멤버',
        kind: 'MEMBER',
        activityStatus: 'ACTIVE',
      },
    ],
    work: null,
    multipleParticipants: (overrides.participants?.length ?? 1) >= 2,
    isSpecialEvent: false,
    specialEventLabel: null,
    viewCount: null,
    viewsObservedAt: null,
    ...overrides,
  }
}

export const test = base.extend<{ catalogMocks: void }>({
  catalogMocks: [
    async ({ page }, use) => {
      await page.route('**/api/v1/**', async (route) => {
        const pathname = new URL(route.request().url()).pathname
        if (pathname === '/api/v1/songs/recommendations') {
          await route.fulfill({ json: { items: [] } })
        } else if (
          pathname === '/api/v1/songs' ||
          pathname === '/api/v1/members'
        ) {
          await route.fulfill({ json: emptyPage })
        } else {
          await route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
        }
      })
      await use()
    },
    { auto: true },
  ],
})
export { expect }
