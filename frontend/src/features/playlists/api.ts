import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas, PlaylistSummary } from '@/shared/api/types'

export type Playlist = PlaylistSummary
export type PlaylistPage =
  ApiSchemas['com.stelody.playlist.dto.PlaylistDtos.Page']
export type PlaylistItems =
  ApiSchemas['com.stelody.playlist.dto.PlaylistDtos.Items']
export type PlaylistItem =
  ApiSchemas['com.stelody.playlist.dto.PlaylistDtos.Item']
type Added = ApiSchemas['com.stelody.playlist.dto.PlaylistDtos.Added']
export const playlistNameSchema = z
  .string()
  .trim()
  .min(1, '이름을 입력해 주세요.')
  .max(50, '이름은 50자까지 입력할 수 있어요.')

function params(cursor: string | null) {
  const query = new URLSearchParams({ size: '20' })
  if (cursor) query.set('cursor', cursor)
  return query
}
export function getPlaylists(
  cursor: string | null,
  songId?: string,
  signal?: AbortSignal,
) {
  const query = params(cursor)
  if (songId) query.set('songId', songId)
  return apiClient.request<PlaylistPage>(
    `/me/playlists?${query}`,
    signal ? { signal } : {},
  )
}
export function getPlaylist(id: string, signal?: AbortSignal) {
  return apiClient.request<Playlist>(
    `/me/playlists/${encodeURIComponent(id)}`,
    signal ? { signal } : {},
  )
}
export function getPlaylistItems(
  id: string,
  cursor: string | null,
  signal?: AbortSignal,
) {
  return apiClient.request<PlaylistItems>(
    `/me/playlists/${encodeURIComponent(id)}/items?${params(cursor)}`,
    signal ? { signal } : {},
  )
}
export async function createPlaylist(name: string) {
  const result = await apiClient.request<Playlist>('/me/playlists', {
    method: 'POST',
    body: { name: playlistNameSchema.parse(name) },
  })
  if (
    !z.uuid().safeParse(result.id).success ||
    !Number.isSafeInteger(result.version) ||
    result.version < 0
  )
    throw new ApiResponseError()
  return result
}
export function addPlaylistSong(playlist: Playlist, songId: string) {
  return apiClient.request<Added>(
    `/me/playlists/${encodeURIComponent(playlist.id)}/items`,
    { method: 'POST', body: { songId, version: playlist.version } },
  )
}

export function renamePlaylist(playlist: Playlist, name: string) {
  return apiClient.request<Playlist>(
    `/me/playlists/${encodeURIComponent(playlist.id)}`,
    {
      method: 'PATCH',
      body: { name: playlistNameSchema.parse(name), version: playlist.version },
    },
  )
}

export function deletePlaylist(playlist: Playlist) {
  return apiClient.request<void>(
    `/me/playlists/${encodeURIComponent(playlist.id)}?${new URLSearchParams({ version: String(playlist.version) })}`,
    { method: 'DELETE' },
  )
}

export function removePlaylistItem(playlist: Playlist, itemId: string) {
  return apiClient.request<Playlist>(
    `/me/playlists/${encodeURIComponent(playlist.id)}/items/${encodeURIComponent(itemId)}?${new URLSearchParams({ version: String(playlist.version) })}`,
    { method: 'DELETE' },
  )
}

export function savePlaylistOrder(playlist: Playlist, itemIds: string[]) {
  return apiClient.request<Playlist>(
    `/me/playlists/${encodeURIComponent(playlist.id)}/order`,
    { method: 'PUT', body: { itemIds, version: playlist.version } },
  )
}
