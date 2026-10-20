import {
  useMutation,
  useIsMutating,
  useQueryClient,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { endLocalSession } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { ApiError } from '@/shared/api/errors'
import { storePlaylistAction } from '@/features/auth/pending-action'
import { addPlaylistSong, createPlaylist, type Playlist } from './api'
import {
  isPlaylistAccount,
  playlistKeys,
  refreshPlaylists,
  playlistDetailOptions,
} from './queries'

type Write =
  | { kind: 'create'; name: string; songId?: string }
  | { kind: 'add'; playlist: Playlist; songId: string }
export function usePlaylistWrite(user: string, returnTo: string) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const key = playlistKeys.write(user)
  const pending = useIsMutating({ mutationKey: key, exact: true }) > 0
  const mutation = useMutation({
    mutationKey: key,
    mutationFn: async (value: Write) => {
      if (!isPlaylistAccount(client, user))
        throw new Error('계정이 변경되었습니다.')
      return value.kind === 'create'
        ? createPlaylist(value.name)
        : (await addPlaylistSong(value.playlist, value.songId)).playlist
    },
    onSuccess: async (playlist, value) => {
      await refreshPlaylists(
        client,
        user,
        playlist,
        value.kind === 'add' ? value.songId : undefined,
      )
    },
    onError: async (error, value) => {
      if (!isPlaylistAccount(client, user)) return
      if (error instanceof ApiError && error.status === 401) {
        if (value.songId)
          storePlaylistAction({
            songId: value.songId,
            returnTo,
            expectedUserId: user,
          })
        await endLocalSession(client)
        void navigate(loginPath(returnTo))
      } else {
        if (
          value.kind === 'add' &&
          error instanceof ApiError &&
          error.code === 'PLAYLIST_CHANGED'
        ) {
          // Refresh the displayed version; retry still requires another user action.
          await client
            .fetchQuery({
              ...playlistDetailOptions(user, value.playlist.id),
              staleTime: 0,
            })
            .catch(() => {})
        }
        await refreshPlaylists(client, user)
      }
    },
  })
  return {
    pending,
    error: mutation.error,
    async write(value: Write) {
      if (!user || client.isMutating({ mutationKey: key, exact: true }))
        return undefined
      const result = await mutation.mutateAsync(value)
      return isPlaylistAccount(client, user) ? result : undefined
    },
  }
}
