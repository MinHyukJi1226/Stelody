import { apiClient } from '@/shared/api/client'
import type { ApiPaths, ApiSchemas, SongPage } from '@/shared/api/types'

export type Song = ApiSchemas['com.stelody.song.dto.SongDtos.Card']
export type SongListQuery = Omit<
  NonNullable<ApiPaths['/api/v1/songs']['get']['parameters']['query']>,
  'collaboration'
>
type Recommendations =
  ApiSchemas['com.stelody.song.dto.SongDtos.Recommendations']

export function getSongs(query: SongListQuery, signal?: AbortSignal) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined) continue
    for (const item of Array.isArray(value) ? value : [value]) {
      params.append(key, String(item))
    }
  }
  return apiClient.request<SongPage>(
    `/songs?${params}`,
    signal ? { signal } : {},
  )
}

export function getRecommendations(signal?: AbortSignal) {
  return apiClient.request<Recommendations>(
    '/songs/recommendations?size=4',
    signal ? { signal } : {},
  )
}
