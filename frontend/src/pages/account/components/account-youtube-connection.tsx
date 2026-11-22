import { accountPath } from '@/features/account/api'
import { useYouTubeConnection } from '@/features/youtube/use-youtube-connection'
import { DisconnectDialog } from '@/pages/youtube/components/disconnect-dialog'
import { Button } from '@/shared/ui/button'

const labels = {
  DISCONNECTED: '미연결됨',
  CONNECTED: '연결 완료',
  RECONNECT_REQUIRED: '재연결 필요',
  REVOKING: '해제 처리 중',
  DISABLED: '현재 사용 불가',
  LOADING: '연결 상태 확인 중',
  STARTING: 'Google 인증을 여는 중이에요',
  ERROR: '상태 조회 오류',
}

export function AccountYouTubeConnection({ user }: { user: string }) {
  const connection = useYouTubeConnection(user, accountPath)
  const { state, query, busy } = connection
  const tone =
    state === 'CONNECTED'
      ? 'text-green'
      : state === 'DISCONNECTED' || state === 'ERROR'
        ? 'text-error'
        : state === 'RECONNECT_REQUIRED'
          ? 'text-warning'
          : 'text-muted'

  return (
    <section
      aria-labelledby="account-youtube-title"
      className="flex w-full flex-col items-start gap-3"
    >
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="account-youtube-title" className="text-lg font-bold md:text-xl">
          YouTube 연결
        </h2>
        <span
          className={`text-xs ${tone}`}
          role="status"
          aria-label="YouTube 연결 상태"
        >
          {labels[state]}
        </span>
      </div>
      <p className="text-muted">
        플레이리스트 내보내기에 사용할 YouTube 연결을 관리합니다.
      </p>
      {query.isError && (
        <p role="alert" className="text-error">
          연결 상태를 불러오지 못했어요.
        </p>
      )}
      {connection.notice && (
        <p
          role={connection.failed ? 'alert' : 'status'}
          className={connection.failed ? 'text-error' : 'text-muted'}
        >
          {connection.notice}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {state === 'CONNECTED' ? (
          <Button
            variant="destructive"
            disabled={busy}
            onClick={connection.openDisconnect}
          >
            연결 해제
          </Button>
        ) : state === 'ERROR' ? (
          <Button
            variant="primary"
            disabled={busy}
            onClick={() => void query.refetch()}
          >
            다시 시도
          </Button>
        ) : (
          <Button
            variant="primary"
            disabled={
              busy || !['DISCONNECTED', 'RECONNECT_REQUIRED'].includes(state)
            }
            onClick={() => void connection.connect()}
          >
            {state === 'RECONNECT_REQUIRED' ? '다시 연결' : 'YouTube 연결'}
          </Button>
        )}
      </div>
      {connection.deleting && (
        <DisconnectDialog
          user={user}
          returnTo={accountPath}
          onClose={connection.closeDisconnect}
          onVerify={connection.verifyDisconnect}
          onComplete={connection.completeDisconnect}
        />
      )}
    </section>
  )
}
