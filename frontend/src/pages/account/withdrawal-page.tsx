import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { apiClient } from '@/shared/api/client'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { endLocalSession, sessionOptions } from '@/features/auth/session'
import { isConfirmed, prepareWithdrawal } from '@/features/account/api'
import {
  clearWithdrawalReceipt,
  readWithdrawalReceipt,
  saveWithdrawalReceipt,
  type StoredWithdrawal,
} from '@/features/account/withdrawal-receipt'
import {
  useAccountActions,
  withdrawalConfirmationOptions,
} from '@/features/account/queries'
import {
  cleanReauthenticationReturn,
  readReauthenticationError,
  readReauthenticationResult,
} from '@/features/account/oauth-result'
import { getActiveExport, exportPath } from '@/features/youtube-export/api'
import { getYouTubeConnection } from '@/features/youtube/api'
import {
  AccountCard,
  AccountLayout,
  AccountSkeleton,
} from './components/account-layout'
import { WithdrawalPanel } from './components/withdrawal-panel'
import { WithdrawalOutcome } from './components/withdrawal-outcome'
import type { WithdrawalView } from './withdrawal-view'

export function WithdrawalPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const [callback] = useState(() => {
    const params = new URLSearchParams(location.search)
    return {
      result: readReauthenticationResult(params),
      error: readReauthenticationError(params),
    }
  })
  const session = useQuery(sessionOptions)
  const client = useQueryClient()
  const [completed, setCompleted] = useState<string>()
  const [receipt, setReceipt] = useState(readWithdrawalReceipt)
  const [rejected, setRejected] = useState<WithdrawalView | null>(null)
  const [outcomeView, setOutcomeView] = useState<'unknown' | 'server' | null>(
    null,
  )
  const otherAccount =
    receipt.kind === 'ready' &&
    !!session.data &&
    session.data.id !== receipt.receipt.user
  const identity =
    session.data === undefined ? undefined : (session.data?.id ?? null)
  const [previousIdentity, setPreviousIdentity] = useState(identity)
  if (identity !== previousIdentity || otherAccount) {
    setPreviousIdentity(identity)
    if (otherAccount) setReceipt({ kind: 'none' })
    else if (identity === null && receipt.kind === 'none' && !completed) {
      const stored = readWithdrawalReceipt()
      if (stored.kind !== 'none') setReceipt(stored)
    }
  }
  useEffect(() => {
    const stored = readWithdrawalReceipt()
    if (
      stored.kind === 'ready' &&
      session.data &&
      session.data.id !== stored.receipt.user
    )
      clearWithdrawalReceipt()
  }, [session.data])
  async function complete(user: string) {
    clearWithdrawalReceipt()
    setReceipt({ kind: 'none' })
    setCompleted(user)
    const current = client.getQueryData(sessionOptions.queryKey)
    if (current === null || current?.id === user) await endLocalSession(client)
  }

  useEffect(() => {
    const clean = cleanReauthenticationReturn(
      new URLSearchParams(location.search),
    ).toString()
    if (clean === new URLSearchParams(location.search).toString()) return
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

  return (
    <AccountLayout title="회원 탈퇴">
      {completed && (!session.data || session.data.id === completed) ? (
        <WithdrawalPanel view="completed" busy={false} />
      ) : receipt.kind === 'ready' && !otherAccount && !session.isPending ? (
        <WithdrawalOutcome
          receipt={receipt.receipt}
          initialView={outcomeView}
          onComplete={complete}
          onRejected={(code) => {
            setRejected(
              code === 'REAUTHENTICATION_REQUIRED'
                ? 'expired'
                : code === 'YOUTUBE_EXPORT_BUSY'
                  ? 'youtube'
                  : code === 'YOUTUBE_REVOCATION_PENDING'
                    ? 'revoking'
                    : 'rejected',
            )
            setReceipt({ kind: 'none' })
          }}
        />
      ) : receipt.kind === 'expired' || receipt.kind === 'unavailable' ? (
        <WithdrawalPanel
          view="unavailable"
          busy={false}
          onCheck={
            receipt.kind === 'expired'
              ? undefined
              : () => setReceipt(readWithdrawalReceipt())
          }
        />
      ) : session.isPending ? (
        <AccountCard loading>
          <p className="font-medium text-brand">계정 정보 조회 중</p>
          <h2 className="text-lg font-bold">계정 정보를 확인하고 있어요</h2>
          <AccountSkeleton />
          <p className="text-muted">잠시 기다려 주세요.</p>
        </AccountCard>
      ) : session.isError ? (
        <AccountLoadError onRetry={() => void session.refetch()} />
      ) : !session.data ? (
        <WithdrawalPanel view="session" busy={false} />
      ) : (
        <WithdrawalFlow
          key={session.data.id}
          user={session.data.id}
          email={session.data.email}
          callback={callback}
          initialView={rejected}
          onOutcome={(value, failureView = null) => {
            setOutcomeView(failureView)
            setReceipt({ kind: 'ready', receipt: value })
          }}
          onComplete={() => complete(session.data!.id)}
        />
      )}
    </AccountLayout>
  )
}

function WithdrawalFlow({
  user,
  email,
  callback,
  initialView,
  onOutcome,
  onComplete,
}: {
  user: string
  email: string
  callback: {
    result: ReturnType<typeof readReauthenticationResult>
    error: ReturnType<typeof readReauthenticationError>
  }
  initialView: WithdrawalView | null
  onOutcome: (
    receipt: StoredWithdrawal,
    initialView?: 'unknown' | 'server' | null,
  ) => void
  onComplete: () => Promise<void>
}) {
  const client = useQueryClient()
  const [view, setView] = useState<WithdrawalView | null>(initialView)
  const [jobPath, setJobPath] = useState<string>()
  const [now, setNow] = useState(Date.now)
  const query = useQuery({
    ...withdrawalConfirmationOptions(user),
    enabled: view === null,
  })
  const { refetch } = query
  const action = useAccountActions(user)
  const controller = useRef<AbortController | null>(null)
  const mounted = useRef(false)
  const lock = useRef(false)
  const current = () =>
    mounted.current && client.getQueryData(sessionOptions.queryKey)?.id === user

  useEffect(() => {
    mounted.current = true
    controller.current = new AbortController()
    return () => {
      mounted.current = false
      controller.current?.abort()
    }
  }, [])

  const expiry = query.data?.expiresAt
  useEffect(() => {
    if (!expiry) return
    const remaining = Date.parse(expiry) - Date.now()
    const timer = window.setTimeout(
      () => setNow(Date.now()),
      Math.max(0, Math.min(remaining, 2147483647)),
    )
    return () => window.clearTimeout(timer)
  }, [expiry])

  useEffect(() => {
    function restored(event: PageTransitionEvent) {
      if (!event.persisted) return
      const stored = readWithdrawalReceipt()
      if (stored.kind === 'ready') {
        onOutcome(stored.receipt)
        return
      }
      lock.current = false
      setView(null)
      apiClient.invalidateCsrf()
      void refetch()
    }
    window.addEventListener('pageshow', restored)
    return () => window.removeEventListener('pageshow', restored)
  }, [refetch, onOutcome])

  const confirmed = !!query.data && isConfirmed(query.data, now)
  const normal: WithdrawalView = query.isPending
    ? 'loading'
    : callback.result === 'cancelled'
      ? 'cancelled'
      : callback.error === 'SESSION_EXPIRED'
        ? 'session'
        : callback.error === 'REAUTHENTICATION_ACCOUNT_MISMATCH'
          ? 'wrong'
          : callback.result === 'failed'
            ? 'failed'
            : confirmed
              ? 'verified'
              : callback.result === 'success' || query.data?.reauthenticated
                ? 'expired'
                : 'guide'
  const displayed =
    view === 'verified' && !confirmed ? 'expired' : view || normal
  const busy =
    ['starting', 'submitting', 'checkingDelete', 'checkingOutcome'].includes(
      displayed,
    ) || query.isFetching

  async function authorizationFailure(error: unknown) {
    if (!current()) return
    if (error instanceof ApiError && error.status === 401) {
      await endLocalSession(client)
      return
    }
    setView('failed')
  }

  async function authorize() {
    if (lock.current || busy || !current()) return
    lock.current = true
    setView('starting')
    try {
      const response = await action.run('authorize', controller.current!.signal)
      if (!current() || !response.accepted || !response.value) return
      window.location.assign(response.value.authorizationUrl)
    } catch (error) {
      await authorizationFailure(error)
      lock.current = false
    }
  }

  async function withdraw() {
    if (lock.current || busy || displayed !== 'verified' || !current()) return
    if (!query.data || !isConfirmed(query.data)) {
      setView('expired')
      return
    }
    lock.current = true
    setView('submitting')
    let receipt: StoredWithdrawal | undefined
    try {
      const signal = controller.current!.signal
      const prepared = await prepareWithdrawal(signal)
      if (!current()) return
      if (!isConfirmed(query.data)) {
        setView('expired')
        return
      }
      const tracked = { ...prepared, user }
      try {
        saveWithdrawalReceipt(tracked)
      } catch {
        setView('storage')
        return
      }
      receipt = tracked
      const response = await action.run('withdraw', signal, receipt.id)
      if (!current() || !response.accepted) return
      await onComplete()
    } catch (error) {
      if (!current()) return
      if (receipt) {
        if (error instanceof ApiError && error.status === 401)
          await endLocalSession(client)
        onOutcome(receipt, error instanceof ApiError ? 'server' : 'unknown')
      } else if (error instanceof ApiError && error.status === 401)
        await endLocalSession(client)
      else setView('storage')
    } finally {
      lock.current = false
    }
  }

  async function checkAvailability() {
    if (lock.current || busy || !current()) return
    lock.current = true
    setView('checkingDelete')
    try {
      const signal = controller.current!.signal
      const [active, connection, response] = await Promise.all([
        getActiveExport(signal),
        getYouTubeConnection(signal),
        refetch(),
      ])
      if (!current()) return
      if (response.error) throw response.error
      if (!response.data) throw new Error('재인증 상태 조회 실패')
      setNow(Date.now())
      if (active.job && ['QUEUED', 'RUNNING'].includes(active.job.status)) {
        setJobPath(exportPath(active.job.playlistId, active.job.id))
        setView('youtube')
      } else if (connection.status === 'REVOKING') setView('revoking')
      else setView(isConfirmed(response.data) ? 'verified' : 'expired')
    } catch (error) {
      if (!current()) return
      if (error instanceof ApiError && error.status === 401)
        await endLocalSession(client)
      else setView('rejected')
    } finally {
      lock.current = false
    }
  }

  if (query.isError && view === null)
    return <AccountLoadError onRetry={() => void refetch()} />
  return (
    <WithdrawalPanel
      view={displayed}
      email={email}
      busy={busy}
      onAuthorize={() => void authorize()}
      onWithdraw={() => void withdraw()}
      onCheck={
        displayed === 'storage'
          ? () => setView(null)
          : () => void checkAvailability()
      }
      jobPath={jobPath}
    />
  )
}

function AccountLoadError({ onRetry }: { onRetry: () => void }) {
  return (
    <AccountCard>
      <p className="font-medium text-error">계정 정보 조회 오류</p>
      <h2 className="text-lg font-bold">계정 정보를 불러오지 못했어요</h2>
      <p className="text-muted">잠시 후 다시 시도해 주세요.</p>
      <Button variant="primary" onClick={onRetry}>
        다시 시도
      </Button>
    </AccountCard>
  )
}
