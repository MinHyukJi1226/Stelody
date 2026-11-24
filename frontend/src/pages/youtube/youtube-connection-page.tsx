import { useQuery } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { safeReturnPath } from '@/features/auth/return-path'
import { accountPath, withdrawalPath } from '@/features/account/api'
import { useYouTubeConnection } from '@/features/youtube/use-youtube-connection'
import { Button } from '@/shared/ui/button'
import { SiteFooter } from '@/shared/ui/site-footer'
import { ConnectionPanel } from './components/connection-panel'
import { DisconnectDialog } from './components/disconnect-dialog'

export function YouTubeConnectionPage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  return session.data ? (
    <ConnectionScreen key={session.data.id} user={session.data.id} />
  ) : null
}

function ConnectionScreen({ user }: { user: string }) {
  const location = useLocation()
  const returnValue = new URLSearchParams(location.search).get('returnTo')
  const safeReturn = safeReturnPath(returnValue)
  const returnTo =
    safeReturn === accountPath ||
    safeReturn === withdrawalPath ||
    /^\/library\/playlists\/[0-9a-f-]+\/youtube-export(?:\?|$)/i.test(
      safeReturn,
    )
      ? safeReturn
      : '/library/playlists'
  const authorizationReturn =
    returnTo === '/library/playlists'
      ? '/library/youtube'
      : `/library/youtube?${new URLSearchParams({ returnTo })}`
  const connection = useYouTubeConnection(user, authorizationReturn)
  const { query, state, notice, failed, busy } = connection
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section className="flex min-w-0 flex-1 flex-col items-center gap-6">
        <h1 className="w-full break-words text-[32px] font-bold leading-[1.5]">
          YouTube 연결 관리
        </h1>
        <p className="w-full text-muted">
          YouTube 계정의 연결 상태를 관리합니다.
        </p>
        <ConnectionPanel
          state={state}
          busy={busy}
          failed={failed}
          notice={
            notice && (
              <p
                role={failed ? 'alert' : 'status'}
                className={`w-full rounded-lg p-3 text-sm leading-[1.5] ${failed ? 'bg-rose text-error' : 'bg-tint text-ink'}`}
              >
                {notice}
              </p>
            )
          }
          onConnect={() => void connection.connect()}
          onDisconnect={connection.openDisconnect}
          onRetry={() => void query.refetch()}
        />
        <Button asChild>
          <Link to={returnTo}>
            {returnTo === '/library/playlists'
              ? '플레이리스트로 돌아가기'
              : returnTo === accountPath
                ? '계정 관리로 돌아가기'
                : returnTo === withdrawalPath
                  ? '탈퇴 화면으로 돌아가기'
                  : '내보내기로 돌아가기'}
          </Link>
        </Button>
      </section>
      <SiteFooter />
      {connection.deleting && (
        <DisconnectDialog
          user={user}
          onClose={connection.closeDisconnect}
          onVerify={connection.verifyDisconnect}
          onComplete={connection.completeDisconnect}
        />
      )}
    </div>
  )
}
