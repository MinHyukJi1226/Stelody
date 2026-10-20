import { useEffect } from 'react'
import { useLocation } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import {
  consumePendingAction,
  readPendingAction,
} from '@/features/auth/pending-action'
import { safeReturnPath } from '@/features/auth/return-path'
import { useFavoriteChange } from './use-favorite-change'

export function FavoriteResume() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const location = useLocation()
  const returnTo = safeReturnPath(
    `${location.pathname}${location.search}${location.hash}`,
  )
  const pending = readPendingAction()
  if (
    !session.data ||
    session.isError ||
    session.isFetching ||
    !pending ||
    pending.kind !== 'favorite' ||
    pending.readyUserId !== session.data.id ||
    pending.returnTo !== returnTo ||
    location.pathname.startsWith('/login')
  )
    return null
  return <ResumeFavorite songId={pending.songId} returnTo={returnTo} />
}

function ResumeFavorite({
  songId,
  returnTo,
}: {
  songId: string
  returnTo: string
}) {
  const { userId, change, pending } = useFavoriteChange(songId)
  useEffect(() => {
    if (pending) return
    const action = consumePendingAction(userId, returnTo)
    if (action?.kind === 'favorite') change(action.favorited)
  }, [userId, returnTo, change, pending])
  return null
}
