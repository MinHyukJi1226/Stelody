import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export type FavoritePage =
  ApiSchemas['com.stelody.favorite.dto.FavoriteDtos.Page']
export type FavoriteItem =
  ApiSchemas['com.stelody.favorite.dto.FavoriteDtos.Item']
export type FavoriteState =
  ApiSchemas['com.stelody.favorite.dto.FavoriteDtos.State']
const stateSchema = z.object({ songId: z.uuid(), favorited: z.boolean() })

export async function getFavoriteState(
  songId: string,
  signal?: AbortSignal,
): Promise<FavoriteState> {
  const value = await apiClient.request<unknown>(
    `/me/favorites/${encodeURIComponent(songId)}`,
    signal ? { signal } : {},
  )
  const parsed = stateSchema.safeParse(value)
  if (!parsed.success || parsed.data.songId !== songId)
    throw new ApiResponseError()
  return parsed.data
}

export function getFavorites(cursor: string | null, signal?: AbortSignal) {
  const params = new URLSearchParams({ size: '20' })
  if (cursor) params.set('cursor', cursor)
  return apiClient.request<FavoritePage>(
    `/me/favorites?${params}`,
    signal ? { signal } : {},
  )
}

export function setFavorite(songId: string, favorited: boolean) {
  return apiClient.request<void>(
    `/me/favorites/${encodeURIComponent(songId)}`,
    { method: favorited ? 'PUT' : 'DELETE' },
  )
}
