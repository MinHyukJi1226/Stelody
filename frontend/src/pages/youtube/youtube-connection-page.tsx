import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { apiClient } from '@/shared/api/client'
import {
  cleanYouTubeReturn,
  readYouTubeFailureCode,
  readYouTubeResult,
  youtubeErrorMessage,
} from '@/features/youtube/oauth-result'
import {
  youtubeConnectionOptions,
  useYouTubeActions,
} from '@/features/youtube/queries'
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
  const navigate = useNavigate()
  const client = useQueryClient()
  const [result, setResult] = useState(() =>
    readYouTubeResult(new URLSearchParams(location.search)),
  )
  const [error, setError] = useState<string>()
  const [deleting, setDeleting] = useState(false)
  const [unlinked, setUnlinked] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const query = useQuery(youtubeConnectionOptions(user))
  const { refetch } = query
  const action = useYouTubeActions(user)
  const current = query.isError ? undefined : query.data
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    const clean = cleanYouTubeReturn(
      new URLSearchParams(location.search),
    ).toString()
    const original = new URLSearchParams(location.search).toString()
    if (clean === original) return
    if (import.meta.env.DEV) {
      const code = readYouTubeFailureCode(new URLSearchParams(location.search))
      if (code) console.warn('[YouTube OAuth callback]', code)
    }
    apiClient.invalidateCsrf()
    void navigate(
      {
        pathname: location.pathname,
        search: clean ? `?${clean}` : '',
        hash: location.hash,
      },
      { replace: true },
    )
  }, [location.pathname, location.search, location.hash, navigate])

  useEffect(() => {
    function restored(event: PageTransitionEvent) {
      if (!event.persisted) return
      setLeaving(false)
      void refetch()
    }
    window.addEventListener('pageshow', restored)
    return () => window.removeEventListener('pageshow', restored)
  }, [refetch])

  async function connect() {
    if (
      action.pending ||
      leaving ||
      query.isFetching ||
      query.isError ||
      !current ||
      deleting ||
      !['DISCONNECTED', 'RECONNECT_REQUIRED'].includes(current.status)
    )
      return
    setResult(null)
    setUnlinked(false)
    setError(undefined)
    try {
      const response = await action.run('authorize')
      if (!mounted.current) return
      if (response?.kind === 'authorize') {
        setLeaving(true)
        window.location.assign(response.value.authorizationUrl)
      }
    } catch (failure) {
      if (!mounted.current) return
      setLeaving(false)
      setError(youtubeErrorMessage(failure))
      void query.refetch()
    }
  }
  const starting = (action.pending && !deleting) || leaving
  const state = starting
    ? 'STARTING'
    : query.isPending
      ? 'LOADING'
      : !current
        ? 'ERROR'
        : current.status
  const notice =
    error ||
    (unlinked &&
    (current?.status === 'DISCONNECTED' || current?.status === 'DISABLED')
      ? 'YouTube 연결을 해제했습니다. 내보내기 작업 기록이 삭제되었습니다.'
      : result === 'success' && !query.isFetching
        ? current?.status === 'CONNECTED'
          ? 'YouTube 계정을 연결했습니다.'
          : 'YouTube 연결 상태를 확인해 주세요.'
        : result === 'cancelled'
          ? '연결을 취소했습니다. 필요할 때 다시 연결해 주세요.'
          : result === 'failed'
            ? 'YouTube 연결에 실패했습니다. 다시 시도해 주세요.'
            : undefined)
  const failed =
    !!error ||
    result === 'failed' ||
    (result === 'success' &&
      !query.isFetching &&
      !!current &&
      current.status !== 'CONNECTED')
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
          busy={action.pending || leaving || query.isFetching || deleting}
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
          onConnect={() => void connect()}
          onDisconnect={() => {
            setError(undefined)
            setResult(null)
            setUnlinked(false)
            setDeleting(true)
          }}
          onRetry={() => void query.refetch()}
        />
        <Button asChild>
          <Link to="/library/playlists">플레이리스트로 돌아가기</Link>
        </Button>
      </section>
      <SiteFooter />
      {deleting && (
        <DisconnectDialog
          user={user}
          onClose={() => setDeleting(false)}
          onVerify={async () => {
            const response = await query.refetch()
            if (response.error) throw response.error
            if (
              !response.data ||
              client.getQueryData(sessionOptions.queryKey)?.id !== user
            )
              throw new Error('연결 상태를 확인하지 못했습니다.')
            return response.data
          }}
          onComplete={() => {
            setDeleting(false)
            setUnlinked(true)
          }}
        />
      )}
    </div>
  )
}
