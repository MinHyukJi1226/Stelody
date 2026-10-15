import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { getRecommendations, getSongs, type Song } from './api'
import type { SongPage } from '@/shared/api/types'

export const latestSongsOptions = queryOptions({
  queryKey: ['songs', 'latest', 'preview'],
  queryFn: ({ signal }) =>
    getSongs({ mode: 'BROWSE', sort: 'LATEST', size: 4 }, signal),
})

export const recommendationsOptions = queryOptions({
  queryKey: ['songs', 'recommendations'],
  queryFn: ({ signal }) => getRecommendations(signal),
  // Refocusing the tab should not replace the random picks being browsed.
  refetchOnWindowFocus: false,
})

export const songCatalogOptions = infiniteQueryOptions({
  queryKey: ['songs', 'latest', 'catalog'],
  initialPageParam: null as string | null,
  queryFn: ({ pageParam, signal }) =>
    getSongs(
      {
        mode: 'BROWSE',
        sort: 'LATEST',
        size: 20,
        ...(pageParam ? { cursor: pageParam } : {}),
      },
      signal,
    ),
  getNextPageParam: (last, _pages, _lastParam, pageParams) =>
    last.hasNext && last.nextCursor && !pageParams.includes(last.nextCursor)
      ? last.nextCursor
      : undefined,
})

export function uniqueSongs(pages: readonly Pick<SongPage, 'items'>[]): Song[] {
  const seen = new Set<string>()
  return pages.flatMap(({ items }) =>
    items.filter(({ id }) => {
      if (seen.has(id)) return false
      seen.add(id)
      return true
    }),
  )
}
