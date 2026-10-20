import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { sessionOptions, endLocalSession } from '@/features/auth/session'
import { storePendingAction } from '@/features/auth/pending-action'
import { loginPath, safeReturnPath } from '@/features/auth/return-path'
import { ApiError } from '@/shared/api/errors'
import { useToast } from '@/shared/ui/toast-context'
import {
  changeFavorite,
  currentAccount,
  favoriteErrorMessage,
  favoriteListKey,
  favoriteStateKey,
  favoriteWriteKey,
  updateFavoriteCache,
} from './queries'

export function useFavoriteChange(songId: string) {
  const client = useQueryClient()
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const userId = session.data?.id ?? ''
  const location = useLocation()
  const navigate = useNavigate()
  const notify = useToast()
  const returnTo = safeReturnPath(
    `${location.pathname}${location.search}${location.hash}`,
  )
  const writeKey = favoriteWriteKey(userId, songId)
  const pending = useIsMutating({ mutationKey: writeKey, exact: true }) > 0

  function requireLogin(favorited: boolean, expectedUserId?: string) {
    const stored = storePendingAction({
      songId,
      favorited,
      returnTo,
      ...(expectedUserId ? { expectedUserId } : {}),
    })
    notify(
      stored
        ? favorited
          ? '로그인 후 즐겨찾기에 저장합니다.'
          : '로그인 후 즐겨찾기를 해제합니다.'
        : '로그인 후 즐겨찾기를 다시 눌러주세요.',
    )
    navigate(loginPath(returnTo))
  }

  const mutation = useMutation({
    mutationKey: writeKey,
    mutationFn: (value: { userId: string; favorited: boolean }) =>
      changeFavorite(client, value.userId, songId, value.favorited),
    onMutate: async (value) => {
      await Promise.all([
        client.cancelQueries({
          queryKey: favoriteStateKey(value.userId, songId),
          exact: true,
        }),
        client.cancelQueries({
          queryKey: favoriteListKey(value.userId),
          exact: true,
        }),
      ])
    },
    onSuccess: async (_data, value) => {
      await client.cancelQueries({
        queryKey: favoriteStateKey(value.userId, songId),
        exact: true,
      })
      if (!currentAccount(client, value.userId)) return
      updateFavoriteCache(client, value.userId, songId, value.favorited)
      notify(
        value.favorited ? '즐겨찾기에 추가했어요.' : '즐겨찾기를 해제했어요.',
      )
    },
    onError: async (error, value) => {
      if (!currentAccount(client, value.userId)) return
      if (error instanceof ApiError && error.status === 401) {
        await endLocalSession(client)
        requireLogin(value.favorited, value.userId)
      } else notify(favoriteErrorMessage(error), 'error')
    },
  })

  return {
    session,
    userId,
    pending,
    error:
      mutation.isError && mutation.variables?.userId === userId
        ? mutation.error
        : null,
    requireLogin,
    change(favorited: boolean) {
      if (
        !userId ||
        session.isError ||
        client.isMutating({ mutationKey: writeKey, exact: true })
      )
        return
      mutation.mutate({ userId, favorited })
    },
  }
}
