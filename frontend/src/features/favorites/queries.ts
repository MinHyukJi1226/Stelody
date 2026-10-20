import {
  infiniteQueryOptions,
  queryOptions,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query'
import { endLocalSession, sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import {
  getFavoriteState,
  getFavorites,
  setFavorite,
  type FavoritePage,
} from './api'

export const favoriteListKey = (userId: string) =>
  ['private', userId, 'favorites', 'list'] as const
export const favoriteStateKey = (userId: string, songId: string) =>
  ['private', userId, 'favorites', 'state', songId] as const
export const favoriteWriteKey = (userId: string, songId: string) =>
  ['private', userId, 'favorites', 'write', songId] as const
export const currentAccount = (client: QueryClient, userId: string) =>
  client.getQueryData(sessionOptions.queryKey)?.id === userId

async function handleReadError(
  client: QueryClient,
  userId: string,
  error: unknown,
): Promise<never> {
  if (
    currentAccount(client, userId) &&
    error instanceof ApiError &&
    error.status === 401
  )
    await endLocalSession(client)
  throw error
}

export const favoriteStateOptions = (userId: string, songId: string) =>
  queryOptions({
    queryKey: favoriteStateKey(userId, songId),
    queryFn: ({ signal, client }) =>
      getFavoriteState(songId, signal).catch((error) =>
        handleReadError(client, userId, error),
      ),
    staleTime: 30_000,
  })

export const favoriteListOptions = (userId: string) =>
  infiniteQueryOptions({
    queryKey: favoriteListKey(userId),
    initialPageParam: null as string | null,
    queryFn: async ({ pageParam, signal, client }) => {
      try {
        const page = await getFavorites(pageParam, signal)
        signal.throwIfAborted()
        if (currentAccount(client, userId)) {
          for (const item of page.items) {
            if (
              !client.isMutating({
                mutationKey: favoriteWriteKey(userId, item.songId),
                exact: true,
              })
            )
              client.setQueryData(favoriteStateKey(userId, item.songId), {
                songId: item.songId,
                favorited: true,
              })
          }
        }
        return page
      } catch (error) {
        return handleReadError(client, userId, error)
      }
    },
    getNextPageParam: (last, _pages, _last, params) =>
      last.hasNext && last.nextCursor && !params.includes(last.nextCursor)
        ? last.nextCursor
        : undefined,
  })

export async function changeFavorite(
  client: QueryClient,
  userId: string,
  songId: string,
  favorited: boolean,
) {
  if (!currentAccount(client, userId)) throw new Error('계정이 변경되었습니다.')
  try {
    await setFavorite(songId, favorited)
  } catch (error) {
    // An uncertain delivery can still have completed the idempotent write.
    if (
      currentAccount(client, userId) &&
      (!(error instanceof ApiError) || error.status >= 500)
    ) {
      try {
        if ((await getFavoriteState(songId)).favorited === favorited) return
      } catch (verificationError) {
        if (
          verificationError instanceof ApiError &&
          verificationError.status === 401
        )
          throw verificationError
      }
    }
    throw error
  }
}

export function updateFavoriteCache(
  client: QueryClient,
  userId: string,
  songId: string,
  favorited: boolean,
) {
  if (!currentAccount(client, userId)) return
  client.setQueryData(favoriteStateKey(userId, songId), { songId, favorited })
  client.setQueryData<InfiniteData<FavoritePage, string | null>>(
    favoriteListKey(userId),
    (old) => {
      if (!old) return old
      const removed = favorited
        ? undefined
        : old.pages
            .flatMap((p) => p.items)
            .find((item) => item.songId === songId)
      return {
        pages: old.pages.slice(0, 1).map((page) => ({
          ...page,
          items: removed
            ? page.items.filter((item) => item.songId !== songId)
            : page.items,
          totalCount: Math.max(0, page.totalCount - (removed ? 1 : 0)),
          availableCount: Math.max(
            0,
            page.availableCount - (removed?.available ? 1 : 0),
          ),
        })),
        pageParams: old.pageParams.slice(0, 1),
      }
    },
  )
  void client.invalidateQueries({
    queryKey: favoriteListKey(userId),
    exact: true,
  })
}

export function favoriteErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'FAVORITE_LIMIT_REACHED')
      return '즐겨찾기 저장 한도에 도달했어요. 보관함에서 곡을 정리해 주세요.'
    if (error.status === 404)
      return '현재 이용할 수 없는 곡이라 저장할 수 없어요.'
    if (error.status === 403)
      return '즐겨찾기를 변경할 수 없어요. 잠시 후 다시 시도해 주세요.'
  }
  return '즐겨찾기 변경 결과를 확인하지 못했어요. 다시 시도해 주세요.'
}
