import { useCallback } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { isPlaylistAccount, playlistKeys } from './queries'

export function usePlaylistLeaveLock(user: string, locked: boolean) {
  const client = useQueryClient()
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      (locked ||
        client.isMutating({
          mutationKey: playlistKeys.write(user),
          exact: true,
        }) > 0) &&
      isPlaylistAccount(client, user) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (locked && isPlaylistAccount(client, user)) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [client, locked, user],
    ),
  )
  return blocker
}
