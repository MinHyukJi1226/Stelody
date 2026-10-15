import { queryOptions } from '@tanstack/react-query'
import { getRecommendations, getSongs } from './api'

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
