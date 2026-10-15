import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { createQueryClient } from '@/app/query-client'
import { latestSongsOptions } from '@/features/songs/queries'
import type { Song } from '@/features/songs/api'
import { server } from '@/test/server'
import { LatestSongs } from './latest-songs'

const song: Song = {
  id: 'song-1',
  title: '캐시된 최신곡',
  type: 'COVER',
  publishedAt: '2026-10-14T16:30:00Z',
  thumbnailUrl: null,
  participants: [],
  work: null,
  collaboration: false,
  isSpecialEvent: false,
  specialEventLabel: null,
  viewCount: null,
  viewsObservedAt: null,
}

it.each([true, false])(
  '백그라운드 갱신 실패 시 기존 결과를 유지하고 재시도로 회복한다 (곡 있음: %s)',
  async (hasSongs) => {
    const client = createQueryClient()
    client.setQueryDefaults(latestSongsOptions.queryKey, { retry: false })
    const cached = {
      items: hasSongs ? [song] : [],
      hasNext: false,
      nextCursor: null,
      totalCount: hasSongs ? 1 : 0,
    }
    client.setQueryData(latestSongsOptions.queryKey, cached)
    let fail = true
    server.use(
      http.get('*/api/v1/songs', () =>
        fail
          ? HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 })
          : HttpResponse.json({
              ...cached,
              items: [{ ...song, title: '갱신된 최신곡' }],
              totalCount: 1,
            }),
      ),
    )
    const user = userEvent.setup()
    const view = render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <LatestSongs />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    try {
      await act(async () => {
        await client.invalidateQueries({
          queryKey: latestSongsOptions.queryKey,
        })
      })
      await waitFor(() =>
        expect(client.getQueryState(latestSongsOptions.queryKey)?.status).toBe(
          'error',
        ),
      )
      expect(
        screen.getByText(
          hasSongs ? '캐시된 최신곡' : '아직 공개된 노래가 없습니다',
        ),
      ).toBeVisible()
      expect(screen.getByText('최신곡을 갱신하지 못했습니다')).toBeVisible()
      expect(
        screen.queryByText('노래를 불러오지 못했습니다'),
      ).not.toBeInTheDocument()
      fail = false
      await user.click(screen.getByRole('button', { name: '다시 시도' }))
      expect(await screen.findByText('갱신된 최신곡')).toBeVisible()
      expect(
        screen.queryByText('최신곡을 갱신하지 못했습니다'),
      ).not.toBeInTheDocument()
    } finally {
      view.unmount()
      client.clear()
    }
  },
)
