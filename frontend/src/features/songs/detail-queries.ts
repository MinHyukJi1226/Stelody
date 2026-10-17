import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import type { ApiSchemas, ApiPaths } from '@/shared/api/types'

export type SongDetail = ApiSchemas['com.stelody.song.dto.SongDtos.Detail']
export type SongViews =
  ApiSchemas['com.stelody.statistics.dto.StatisticsDtos.Views']
export type ViewPeriod = NonNullable<
  ApiPaths['/api/v1/songs/{id}/views']['get']['parameters']['query']
>['days'] &
  number

export const songDetailOptions = (id: string) =>
  queryOptions({
    queryKey: ['songs', 'detail', id],
    queryFn: ({ signal }) =>
      apiClient.request<SongDetail>(`/songs/${encodeURIComponent(id)}`, {
        signal,
      }),
  })

export const songViewsOptions = (
  id: string,
  videoId: string,
  days: ViewPeriod,
) =>
  queryOptions({
    queryKey: ['songs', 'detail', id, 'views', videoId, days],
    queryFn: ({ signal }) =>
      apiClient.request<SongViews>(
        `/songs/${encodeURIComponent(id)}/views?days=${days}`,
        { signal },
      ),
  })
