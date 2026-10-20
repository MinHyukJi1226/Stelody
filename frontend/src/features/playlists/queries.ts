import {
  infiniteQueryOptions,
  queryOptions,
  type QueryClient,
  type InfiniteData,
} from '@tanstack/react-query'
import { sessionOptions, endLocalSession } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import {
  getPlaylist,
  getPlaylistItems,
  getPlaylists,
  type Playlist,
  type PlaylistPage,
  type PlaylistItems,
} from './api'

export const playlistKeys = {
  all: (user: string) => ['private', user, 'playlists'] as const,
  lists: (user: string) => [...playlistKeys.all(user), 'list'] as const,
  list: (user: string, songId?: string) =>
    [...playlistKeys.lists(user), songId ?? null] as const,
  detail: (user: string, id: string) =>
    [...playlistKeys.all(user), 'detail', id] as const,
  items: (user: string, id: string) =>
    [...playlistKeys.all(user), 'items', id] as const,
  write: (user: string) => [...playlistKeys.all(user), 'write'] as const,
}
export const isPlaylistAccount = (client: QueryClient, user: string) =>
  client.getQueryData(sessionOptions.queryKey)?.id === user
async function read<T>(
  client: QueryClient,
  user: string,
  fn: () => Promise<T>,
) {
  try {
    return await fn()
  } catch (error) {
    if (
      isPlaylistAccount(client, user) &&
      error instanceof ApiError &&
      error.status === 401
    )
      await endLocalSession(client)
    throw error
  }
}
const next = (
  last: { hasNext: boolean; nextCursor: string | null },
  _pages: unknown[],
  _last: string | null,
  params: (string | null)[],
) =>
  last.hasNext && last.nextCursor && !params.includes(last.nextCursor)
    ? last.nextCursor
    : undefined
export const playlistListOptions = (user: string, songId?: string) =>
  infiniteQueryOptions({
    queryKey: playlistKeys.list(user, songId),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal, client }) =>
      read(client, user, () => getPlaylists(pageParam, songId, signal)),
    getNextPageParam: next,
  })
export const playlistDetailOptions = (user: string, id: string) =>
  queryOptions({
    queryKey: playlistKeys.detail(user, id),
    queryFn: ({ signal, client }) =>
      read(client, user, () => getPlaylist(id, signal)),
  })
export const playlistItemsOptions = (user: string, id: string) =>
  infiniteQueryOptions({
    queryKey: playlistKeys.items(user, id),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal, client }) =>
      read(client, user, async () => {
        const page = await getPlaylistItems(id, pageParam, signal)
        const first = client.getQueryData<InfiniteData<PlaylistItems>>(
          playlistKeys.items(user, id),
        )?.pages[0]
        if (pageParam && first && first.version !== page.version)
          throw new ApiError(409, { code: 'PLAYLIST_CHANGED' })
        return page
      }),
    getNextPageParam: next,
  })
export async function refreshPlaylists(
  client: QueryClient,
  user: string,
  updated?: Playlist,
  songId?: string,
) {
  if (!isPlaylistAccount(client, user)) return
  await client.cancelQueries({ queryKey: playlistKeys.all(user) })
  if (!isPlaylistAccount(client, user)) return
  if (updated) {
    client.setQueryData(playlistKeys.detail(user, updated.id), updated)
    client.removeQueries({
      queryKey: playlistKeys.items(user, updated.id),
      exact: true,
      type: 'inactive',
    })
  }
  for (const [key] of client.getQueriesData({
    queryKey: playlistKeys.lists(user),
  })) {
    client.setQueryData<InfiniteData<PlaylistPage, string | null>>(
      key,
      (old) =>
        old && {
          pages: old.pages.slice(0, 1).map((page) => ({
            ...page,
            items: page.items.map((item) =>
              item.id === updated?.id
                ? {
                    ...updated,
                    containsSong:
                      songId && key[4] === songId ? true : item.containsSong,
                  }
                : item,
            ),
          })),
          pageParams: old.pageParams.slice(0, 1),
        },
    )
  }
  await client.invalidateQueries({ queryKey: playlistKeys.all(user) })
}
export const isUncertainPlaylistWrite = (error: unknown) =>
  !(error instanceof ApiError) || error.status >= 500
export function playlistErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'PLAYLIST_LIMIT_REACHED':
        return '만들 수 있는 플레이리스트 한도에 도달했어요.'
      case 'PLAYLIST_ITEM_LIMIT_REACHED':
        return '이 목록에 담을 수 있는 곡 수를 초과했어요.'
      case 'PLAYLIST_SONG_ALREADY_EXISTS':
        return '이미 이 목록에 담긴 곡이에요.'
      case 'PLAYLIST_CHANGED':
        return '목록이 변경되었어요. 새로 불러온 목록을 확인하고 다시 담아주세요.'
      case 'PLAYLIST_NOT_FOUND':
        return '목록을 찾을 수 없어요. 목록을 다시 확인해 주세요.'
      case 'CATALOG_NOT_FOUND':
        return '현재 이용할 수 없는 곡이라 담을 수 없어요.'
    }
    if (error.status === 403)
      return '요청을 처리할 수 없어요. 잠시 후 다시 시도해 주세요.'
    if (error.status === 400) return '입력 내용을 확인해 주세요.'
  }
  return '처리 결과를 확인하지 못했어요. 목록을 확인한 뒤 다시 시도해 주세요.'
}
