import {
  useIsMutating,
  useMutation,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { endLocalSession } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { ApiError } from '@/shared/api/errors'
import {
  deletePlaylist,
  removePlaylistItem,
  renamePlaylist,
  type Playlist,
  type PlaylistPage,
} from './api'
import { isPlaylistAccount, playlistKeys, refreshPlaylists } from './queries'

type Change = { playlist: Playlist } & (
  | { kind: 'rename'; name: string }
  | { kind: 'delete' }
  | { kind: 'remove'; itemId: string; songId: string }
)
type Result = { kind: 'deleted' } | { kind: 'saved'; playlist: Playlist }

export function usePlaylistManagement(user: string, returnTo: string) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const key = playlistKeys.write(user)
  const pending = useIsMutating({ mutationKey: key, exact: true }) > 0
  const mutation = useMutation({
    mutationKey: key,
    mutationFn: async (change: Change): Promise<Result> => {
      if (!isPlaylistAccount(client, user))
        throw new Error('계정이 변경되었습니다.')
      switch (change.kind) {
        case 'rename':
          return {
            kind: 'saved',
            playlist: await renamePlaylist(change.playlist, change.name),
          }
        case 'remove':
          return {
            kind: 'saved',
            playlist: await removePlaylistItem(change.playlist, change.itemId),
          }
        case 'delete':
          await deletePlaylist(change.playlist)
          return { kind: 'deleted' }
      }
    },
    onSuccess: async (result, change) => {
      if (!isPlaylistAccount(client, user)) return
      if (result.kind === 'deleted') {
        await client.cancelQueries({ queryKey: playlistKeys.all(user) })
        if (!isPlaylistAccount(client, user)) return
        client.setQueriesData<InfiniteData<PlaylistPage, string | null>>(
          { queryKey: playlistKeys.lists(user) },
          (old) =>
            old && {
              pages: old.pages.slice(0, 1).map((page) => ({
                ...page,
                items: page.items.filter(
                  (item) => item.id !== change.playlist.id,
                ),
                totalCount: Math.max(
                  0,
                  page.totalCount -
                    (old.pages.some((p) =>
                      p.items.some((item) => item.id === change.playlist.id),
                    )
                      ? 1
                      : 0),
                ),
              })),
              pageParams: old.pageParams.slice(0, 1),
            },
        )
        client.removeQueries({
          queryKey: playlistKeys.all(user),
          predicate: (query) =>
            query.queryKey[3] !== 'list' &&
            query.queryKey[4] === change.playlist.id,
        })
        await client.invalidateQueries({ queryKey: playlistKeys.lists(user) })
      } else {
        await refreshPlaylists(
          client,
          user,
          result.playlist,
          change.kind === 'remove' ? change.songId : undefined,
          false,
        )
      }
    },
    onError: async (error) => {
      if (!isPlaylistAccount(client, user)) return
      if (error instanceof ApiError && error.status === 401) {
        await endLocalSession(client)
        void navigate(loginPath(returnTo))
      } else {
        // Keep the edited snapshot until the user explicitly reloads it.
        await client.invalidateQueries({
          queryKey: playlistKeys.all(user),
          refetchType: 'none',
        })
      }
    },
  })
  return {
    pending,
    async write(change: Change) {
      if (!user || client.isMutating({ mutationKey: key, exact: true }))
        return undefined
      const result = await mutation.mutateAsync(change)
      return isPlaylistAccount(client, user) ? result : undefined
    },
  }
}
