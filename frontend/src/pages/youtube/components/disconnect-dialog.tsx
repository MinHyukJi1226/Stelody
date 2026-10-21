import { useId, useState } from 'react'
import type { YouTubeConnection } from '@/features/youtube/api'
import { useYouTubeActions } from '@/features/youtube/queries'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'

export function DisconnectDialog({
  user,
  onClose,
  onVerify,
  onComplete,
}: {
  user: string
  onClose: () => void
  onVerify: () => Promise<YouTubeConnection>
  onComplete: (connection: YouTubeConnection) => void
}) {
  const titleId = useId()
  const action = useYouTubeActions(user)
  const [error, setError] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [checking, setChecking] = useState(false)
  const busy = action.pending || checking
  function dismiss() {
    if (busy) return
    if (uncertain) void onVerify().catch(() => {})
    onClose()
  }
  async function disconnect() {
    if (busy) return
    setError(false)
    try {
      if (uncertain) {
        setChecking(true)
        const current = await onVerify()
        setChecking(false)
        if (
          current.status !== 'CONNECTED' &&
          current.status !== 'RECONNECT_REQUIRED'
        ) {
          onComplete(current)
          return
        }
      }
      const result = await action.run('disconnect')
      if (result?.kind === 'disconnect') onComplete(result.value)
    } catch (failure) {
      setError(true)
      setUncertain(
        (previous) =>
          previous || !(failure instanceof ApiError) || failure.status >= 500,
      )
    } finally {
      setChecking(false)
    }
  }
  return (
    <Dialog
      labelledBy={titleId}
      onDismiss={dismiss}
      busy={busy}
      className="max-w-[480px] p-5 md:p-6 backdrop:bg-black/40"
    >
      <div className="flex flex-col gap-4">
        <h2 id={titleId} className="text-2xl font-bold leading-[1.5]">
          YouTube 연결을 해제할까요?
        </h2>
        <p className="text-sm leading-[1.5]">
          사이트의 내보내기 작업 기록이 삭제됩니다.
        </p>
        <p className="text-xs leading-[1.5] text-muted">
          사이트 플레이리스트와 이미 생성된 YouTube 목록은 유지됩니다.
        </p>
        {busy && (
          <p role="status" className="text-sm leading-[1.5] text-muted">
            연결을 해제하고 있습니다. 잠시 기다려주세요.
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm leading-[1.5] text-error">
            연결 해제에 실패했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button disabled={busy} onClick={dismiss}>
            취소
          </Button>
          <Button
            variant="destructive"
            disabled={busy}
            onClick={() => void disconnect()}
          >
            {busy ? '해제 중…' : error ? '다시 시도' : '해제'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
