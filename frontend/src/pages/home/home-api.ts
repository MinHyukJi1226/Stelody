import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import type { ApiSchemas } from '@/shared/api/types'

export const milestonesOptions = queryOptions({
  queryKey: ['songs', 'milestones'],
  queryFn: ({ signal }) =>
    apiClient.request<ApiSchemas['com.stelody.milestone.MilestoneDtos.Feed']>(
      '/songs/milestones',
      { signal, cache: 'no-store' },
    ),
})

export const upcomingOptions = queryOptions({
  queryKey: ['songs', 'upcoming'],
  queryFn: ({ signal }) =>
    apiClient.request<
      ApiSchemas['com.stelody.schedule.PublicScheduleController.Schedule']
    >('/songs/upcoming', { signal, cache: 'no-store' }),
})

export const trendingOptions = queryOptions({
  queryKey: ['songs', 'trending', 10],
  queryFn: ({ signal }) =>
    apiClient.request<
      ApiSchemas['com.stelody.statistics.dto.StatisticsDtos.Trending']
    >('/songs/trending?size=10', { signal, cache: 'no-store' }),
})
