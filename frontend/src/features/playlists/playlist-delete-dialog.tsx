import { useId, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { type Playlist } from './api'
import { isUncertainPlaylistWrite, playlistErrorMessage } from './queries'
import { usePlaylistManagement } from './use-playlist-management'

export function PlaylistDeleteDialog({
  userId,
  playlist,
  onClose,
  onReload,
}: {
  userId: string
  playlist: Playlist
  onClose: () => void
  onReload: () => void
}) {
  const [confirmed] = useState(playlist)
  const [error, setError] = useState<string>()
  const [reloadRequired, setReloadRequired] = useState(false)
  const titleId = useId()
  const navigate = useNavigate()
  const notify = useToast()
  const action = usePlaylistManagement(
    userId,
    `/library/playlists/${playlist.id}`,
  )
  async function remove() {
    if (action.pending || reloadRequired) return
    setError(undefined)
    try {
      const result = await action.write({ kind: 'delete', playlist: confirmed })
      if (result?.kind === 'deleted') {
        notify('플레이리스트를 삭제했어요.')
        void navigate('/library/playlists', { replace: true })
      }
    } catch (failure) {
      setError(playlistErrorMessage(failure))
      setReloadRequired(
        isUncertainPlaylistWrite(failure) ||
          (failure instanceof ApiError && [404, 409].includes(failure.status)),
      )
    }
  }
  return (
    <Dialog
      labelledBy={titleId}
      onDismiss={onClose}
      busy={action.pending}
      className="p-5 md:max-w-[480px] md:p-6"
    >
      <div className="flex flex-col gap-4">
        <h2 id={titleId} className="text-2xl font-bold leading-[1.5]">
          이 목록을 삭제할까요?
        </h2>
        <p className="break-words text-sm leading-[1.5]">
          ‘{confirmed.name}’ 목록과 담긴 항목 {confirmed.totalCount}개가
          제거됩니다.
        </p>
        <p className="text-xs leading-[1.5] text-muted">
          다른 플레이리스트, 즐겨찾기, 원본 곡은 유지됩니다.
        </p>
        {error && (
          <p role="alert" className="text-sm text-error">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button disabled={action.pending} onClick={onClose}>
            취소
          </Button>
          {reloadRequired ? (
            <Button
              onClick={() => {
                onClose()
                onReload()
              }}
            >
              최신 목록 확인
            </Button>
          ) : (
            <Button
              variant="destructive"
              disabled={action.pending}
              onClick={() => void remove()}
            >
              {action.pending ? '삭제 중…' : '목록 삭제'}
            </Button>
          )}
        </div>
      </div>
    </Dialog>
  )
}
