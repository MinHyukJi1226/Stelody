import { ApiError, ApiResponseError } from '@/shared/api/errors'
import {
  getPlaylist,
  getPlaylistItems,
  type Playlist,
  type PlaylistItem,
} from './api'

export type PlaylistSnapshot = { playlist: Playlist; items: PlaylistItem[] }

export async function getPlaylistSnapshot(
  id: string,
  signal?: AbortSignal,
): Promise<PlaylistSnapshot> {
  const playlist = await getPlaylist(id, signal)
  if (
    playlist.id !== id ||
    !Number.isSafeInteger(playlist.version) ||
    playlist.version < 0
  )
    throw new ApiResponseError()
  const items: PlaylistItem[] = []
  const ids = new Set<string>()
  const cursors = new Set<string>()
  let cursor: string | null = null
  do {
    const page = await getPlaylistItems(id, cursor, signal)
    signal?.throwIfAborted()
    if (
      page.version !== playlist.version ||
      page.totalCount !== playlist.totalCount
    )
      throw new ApiError(409, { code: 'PLAYLIST_CHANGED' })
    for (const item of page.items) {
      if (ids.has(item.id) || item.position !== items.length)
        throw new ApiResponseError()
      ids.add(item.id)
      items.push(item)
    }
    if (items.length > playlist.totalCount) throw new ApiResponseError()
    if (!page.hasNext) break
    if (!page.nextCursor || cursors.has(page.nextCursor) || !page.items.length)
      throw new ApiResponseError()
    cursor = page.nextCursor
    cursors.add(cursor)
  } while (cursor)
  if (items.length !== playlist.totalCount) throw new ApiResponseError()
  return { playlist, items }
}
