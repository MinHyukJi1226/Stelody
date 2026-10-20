import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import {
  consumePendingAction,
  readPendingAction,
  storePlaylistAction,
} from '@/features/auth/pending-action'
import { loginPath, safeReturnPath } from '@/features/auth/return-path'
import { Button } from '@/shared/ui/button'
import { useToast } from '@/shared/ui/toast-context'
import { PlaylistPicker } from './playlist-picker'

export function PlaylistButton({ songId }: { songId: string }) {
  // An explicit close must override the initial login-resume intent.
  const [open, setOpen] = useState<boolean>()
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const location = useLocation()
  const navigate = useNavigate()
  const notify = useToast()
  const returnTo = safeReturnPath(
    `${location.pathname}${location.search}${location.hash}`,
  )
  const pending = readPendingAction()
  const resume =
    pending?.kind === 'playlist' &&
    pending.songId === songId &&
    pending.returnTo === returnTo &&
    pending.readyUserId === session.data?.id
  const close = () => {
    if (resume && session.data) consumePendingAction(session.data.id, returnTo)
    setOpen(false)
  }
  function click() {
    if (session.isError) {
      void session.refetch()
      return
    }
    if (session.data) {
      setOpen(true)
      return
    }
    const stored = storePlaylistAction({ songId, returnTo })
    notify(
      stored
        ? '로그인 후 플레이리스트를 선택해 주세요.'
        : '로그인 후 플레이리스트 버튼을 다시 눌러주세요.',
    )
    void navigate(loginPath(returnTo))
  }
  return (
    <>
      <Button
        disabled={session.isPending || session.isFetching}
        onClick={click}
        className="h-12 min-h-12 w-full min-w-0 border border-line p-3 font-bold leading-5 md:w-44 md:flex-none"
      >
        {session.isError ? '로그인 상태 다시 확인' : '＋ 플레이리스트'}
      </Button>
      {session.data && !session.isError && (open ?? resume) && (
        <PlaylistPicker
          key={session.data.id}
          userId={session.data.id}
          songId={songId}
          returnTo={returnTo}
          onClose={close}
        />
      )}
    </>
  )
}
