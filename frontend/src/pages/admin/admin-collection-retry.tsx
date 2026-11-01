import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError } from '@/features/admin/format'
import { adminKeys } from '@/features/admin/queries'
import { sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import { apiClient } from '@/shared/api/client'
import { AdminApiAccess, AdminTime } from './admin-shared'
import { SongNav, FieldError } from './song-ui'
import { SongLoading } from './admin-songs'
import { useWorkNavigation } from './use-work-navigation'
import {
  retryRunOptions,
  retryUnavailable,
  failureReturnPath,
  runPath,
  getRun,
  getRetryOverview,
  getRetry,
  submitRetry,
  receiptKey,
  readReceipt,
  storeReceipt,
  retryErrorLabels,
  retrySchema,
  type Receipt,
  type Retry,
  type Run,
} from '@/features/admin/collection-retry'

export function AdminCollectionRunPage() {
  const user = useAdminUser(),
    { kind = '', runId = '' } = useParams(),
    [params] = useSearchParams()
  const returnTo = failureReturnPath(params.get('returnTo'))
  const q = useQuery(
    retryRunOptions(
      user,
      kind,
      runId,
      params.get('retryId'),
      params.get('execution') === '1',
    ),
  )
  if (isAdminAccessError(q.error))
    return <AdminApiAccess error={q.error} user={user} />
  if (q.isPending) return <SongLoading />
  if (q.isError)
    return (
      <>
        <SongNav />
        <section className="admin-panel" role="alert">
          <h1 className="admin-heading">
            {q.error instanceof ApiError && q.error.status === 404
              ? '수집 작업을 찾을 수 없습니다'
              : '작업을 조회하지 못했습니다'}
          </h1>
          <Button onClick={() => void q.refetch()}>다시 조회</Button>
          <Link to={returnTo}>실패 목록으로</Link>
        </section>
      </>
    )
  return (
    <RunEditor
      key={`${user}:${kind}:${runId}:${params.get('retryId') ?? ''}:${params.get('execution') === '1'}`}
      user={user}
      initial={q.data}
      returnTo={returnTo}
      execution={params.get('execution') === '1'}
      sourceId={params.get('sourceId')}
    />
  )
}
function RunEditor({
  user,
  initial,
  returnTo,
  execution,
  sourceId,
}: {
  user: string
  initial: {
    run: Run
    overview: Awaited<ReturnType<typeof getRetryOverview>>
    retry: Retry | null
  }
  returnTo: string
  execution: boolean
  sourceId: string | null
}) {
  const client = useQueryClient(),
    key = receiptKey(user, initial.run.kind, initial.run.id)
  const [run, setRun] = useState(initial.run),
    [overview, setOverview] = useState(initial.overview)
  const [recovery] = useState(() => {
    try {
      const saved = readReceipt(key)
      if (
        saved &&
        (saved.kind !== initial.run.kind || saved.runId !== initial.run.id)
      )
        throw new Error('MISMATCH')
      return { saved, error: false }
    } catch {
      return { saved: null, error: true }
    }
  })
  const [reason, setReason] = useState(
      initial.retry || execution ? '' : (recovery.saved?.body.reason ?? ''),
    ),
    [receipt, setReceipt] = useState<Receipt | null>(recovery.saved),
    [result, setResult] = useState<Retry | null>(initial.retry)
  const [busy, setBusy] = useState(false),
    [confirm, setConfirm] = useState(false),
    [unknown, setUnknown] = useState(
      !!recovery.saved && !initial.retry && !execution,
    ),
    [notFound, setNotFound] = useState(false)
  const [notice, setNotice] = useState(
      recovery.error
        ? '보관한 재시도 요청을 읽을 수 없습니다. 새 요청을 전송하지 않았습니다.'
        : '',
    ),
    [fieldError, setFieldError] = useState(''),
    [access, setAccess] = useState<unknown>(null),
    [storageError, setStorageError] = useState(recovery.error)
  const state = useRef({
    mounted: false,
    busy: false,
    abort: null as AbortController | null,
  })
  const dirty = !!reason || unknown
  const nav = useWorkNavigation(
    dirty,
    busy,
    unknown,
    returnTo,
    '접수하지 않은 재시도 사유는 버려집니다. 보관한 요청은 유지됩니다.',
  )
  const session = useQuery(sessionOptions)
  const verified = () =>
    state.current.mounted &&
    client.getQueryState(sessionOptions.queryKey)?.status === 'success' &&
    isActiveAdmin(client.getQueryData(sessionOptions.queryKey)) &&
    client.getQueryData(sessionOptions.queryKey)?.id === user &&
    !client
      .getQueryCache()
      .findAll({ queryKey: adminKeys(user) })
      .some((q) => isAdminAccessError(q.state.error))
  useEffect(() => {
    const r = state.current
    r.mounted = true
    const unsubscribe = client.getQueryCache().subscribe(() => {
      if (!verified()) r.abort?.abort()
    })
    return () => {
      r.mounted = false
      r.abort?.abort()
      unsubscribe()
    }
    // The component is keyed by account and run; callbacks use the current query cache.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, user])
  async function perform(action: (signal: AbortSignal) => Promise<void>) {
    if (state.current.busy || !verified()) return
    state.current.busy = true
    setBusy(true)
    setNotice('')
    const abort = new AbortController()
    state.current.abort = abort
    try {
      await action(abort.signal)
    } catch (e) {
      if (!verified() || abort.signal.aborted) return
      if (isAdminAccessError(e)) setAccess(e)
      else
        setNotice(
          e instanceof ApiError
            ? e.message
            : '조회하지 못했습니다. 기존 입력과 요청 ID를 유지했습니다.',
        )
    } finally {
      state.current.busy = false
      state.current.abort = null
      if (state.current.mounted) setBusy(false)
    }
  }
  function accept(v: Retry) {
    if (!verified()) return
    setResult(v)
    setUnknown(false)
    setReason('')
    setNotFound(false)
    void client.invalidateQueries({
      queryKey: adminKeys(user),
      refetchType: 'none',
    })
  }
  async function check(saved: Receipt) {
    await perform(async (signal) => {
      try {
        const v = await getRetry(saved, signal)
        if (!signal.aborted) accept(v)
      } catch (e) {
        if (
          e instanceof ApiError &&
          e.status === 404 &&
          e.code === 'COLLECTION_RETRY_NOT_FOUND'
        ) {
          setNotFound(true)
          setNotice(
            '해당 요청의 접수 기록이 없습니다. 같은 요청을 재전송할 수 있습니다.',
          )
        } else throw e
      }
    })
  }
  async function send(saved?: Receipt) {
    setConfirm(false)
    if (!verified() || state.current.busy) return
    let value = saved
    if (!value) {
      if (!reason.trim() || reason.length > 500) {
        setFieldError('재시도 사유는 1–500자로 입력해주세요.')
        return
      }
      value = {
        kind: run.kind,
        runId: run.id,
        body: {
          requestId: crypto.randomUUID(),
          attempt: run.attempt,
          reason: reason.trim(),
        },
      }
    }
    try {
      storeReceipt(key, value)
    } catch {
      setStorageError(true)
      setNotice('요청을 탭에 보관할 수 없어 전송하지 않았습니다.')
      return
    }
    setReceipt(value)
    const request = value
    await perform(async (signal) => {
      try {
        const v = await submitRetry(request, signal)
        if (!signal.aborted) accept(v)
      } catch (e) {
        if (!verified() || signal.aborted) return
        if (isAdminAccessError(e)) {
          setUnknown(true)
          setAccess(e)
          return
        }
        if (
          e instanceof ApiError &&
          ((e.status >= 400 && e.status < 500) ||
            e.code === 'COLLECTION_RETRY_DISABLED')
        ) {
          setNotice(retryErrorLabels[e.code] ?? e.message)
          setFieldError(
            e.fieldErrors.find((x) => x.field === 'reason')?.message ?? '',
          )
          try {
            sessionStorage.removeItem(key)
          } catch {
            /* no further write while storage is unavailable */ setStorageError(
              true,
            )
          }
          setReceipt(null)
          setUnknown(false)
          const latest = await getRetryOverview(signal)
          if (!signal.aborted) setOverview(latest)
        } else {
          setUnknown(true)
          setNotFound(false)
          setNotice(
            '접수 결과를 확인할 수 없습니다. 보관한 요청 ID로 결과를 먼저 확인해주세요.',
          )
        }
      }
    })
  }
  const blocked = retryUnavailable(run, overview.checkedAt)
  const locked =
    busy ||
    !session.isSuccess ||
    session.data?.id !== user ||
    !isActiveAdmin(session.data)
  const active = overview.activeRetry
  if (access)
    return (
      <>
        <AdminApiAccess error={access} user={user} />
        {nav.dialog}
      </>
    )
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">
        {execution ? '실행 작업 결과' : '실패 작업 상세'}
      </h1>
      {notice && (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">{notice}</h2>
        </section>
      )}
      {!execution &&
        (result ? (
          <section className="admin-panel" role="status">
            <h2 className="text-lg font-bold">
              {
                {
                  QUEUED: '재시도가 접수되었습니다',
                  RUNNING: '수집 재시도 실행 중',
                  SUCCEEDED: '수집 재시도가 완료되었습니다',
                  FAILED: '재시도 작업에 실패했습니다',
                }[result.status]
              }
            </h2>
            <p>
              요청 상태: {result.status} · 요청 ID {result.id}
            </p>
            <p>
              {result.status === 'QUEUED'
                ? '아직 완료되지 않았습니다. 실행 시각 미정입니다.'
                : '실행 결과와 오류 코드를 확인하세요.'}
            </p>
            <p>{result.errorCode ?? '오류 코드 없음'}</p>
            <p>
              접수 <AdminTime value={result.createdAt} /> · 시작{' '}
              <AdminTime value={result.startedAt} empty="시작 시각 없음" /> ·
              종료{' '}
              <AdminTime value={result.finishedAt} empty="종료 시각 없음" />
            </p>
            <Button
              disabled={locked}
              onClick={() =>
                void perform(async (signal) => {
                  const v = retrySchema.parse(
                    await apiClient.request<unknown>(
                      `/admin/collection-retries/${result.id}`,
                      { signal, cache: 'no-store' },
                    ),
                  )
                  if (
                    v.id !== result.id ||
                    v.kind !== run.kind ||
                    v.runId !== run.id
                  )
                    throw new Error('MISMATCH')
                  if (!signal.aborted) accept(v)
                })
              }
            >
              상태 새로고침
            </Button>
            {result.status === 'FAILED' && (
              <Button
                disabled={locked}
                onClick={() =>
                  void perform(async (signal) => {
                    const current = await getRun(run.kind, run.id, signal)
                    const operations = await getRetryOverview(signal)
                    if (signal.aborted || !verified()) return
                    try {
                      sessionStorage.removeItem(key)
                    } catch {
                      setStorageError(true)
                      setNotice(
                        '보관한 요청을 정리할 수 없어 새 접수를 열지 않았습니다.',
                      )
                      return
                    }
                    setRun(current)
                    setOverview(operations)
                    setReceipt(null)
                    setResult(null)
                    setUnknown(false)
                    setNotFound(false)
                    setReason('')
                  })
                }
              >
                원본 작업 확인
              </Button>
            )}
            {result.executionRunId && (
              <Button asChild>
                <Link
                  to={
                    runPath(result.kind, result.executionRunId, returnTo) +
                    '&execution=1&sourceId=' +
                    run.id
                  }
                >
                  실행 작업 확인
                </Link>
              </Button>
            )}
          </section>
        ) : unknown ? (
          <section className="admin-panel" role="status">
            <h2 className="text-lg font-bold">
              접수 결과를 확인할 수 없습니다
            </h2>
            <p>
              보관한 요청 ID로 결과를 먼저 조회합니다. 조회 실패를 미접수로
              판단하지 않습니다.
            </p>
            <Button
              disabled={locked || !receipt}
              onClick={() => receipt && void check(receipt)}
            >
              최신 결과 확인
            </Button>
            <Button
              disabled={locked || !notFound || !receipt}
              onClick={() => receipt && void send(receipt)}
            >
              같은 요청 재전송
            </Button>
          </section>
        ) : (
          <>
            <section className="admin-panel">
              <h2 className="text-lg font-bold">확인해 주세요</h2>
              <p className="text-sm text-muted">
                실패 목록은 영상 수가 아닌 작업 수입니다. 작업 종류·시도 횟수와
                기간을 확인하세요.
              </p>
            </section>
            <section className="admin-panel">
              <h2 className="text-lg font-bold">확인해 주세요</h2>
              <p className="text-sm text-muted">
                실패·할당량 초과·시간 초과 작업만 요청할 수 있습니다. VIDEO는
                작업 슬롯, DISCOVERY는 시작 시각을 기준으로 48시간 이내여야
                합니다.
              </p>
            </section>
          </>
        ))}
      <h2 className="text-2xl font-bold">수집 원본 · 읽기 전용</h2>
      <RunSource run={run} />
      {!execution && !result && !unknown && (
        <>
          <p className="text-sm text-error">
            {!overview.retryEnabled
              ? '지금은 재시도를 사용할 수 없습니다'
              : blocked}
          </p>
          {active && (
            <section className="admin-panel">
              <h2 className="text-lg font-bold">
                이미 진행 중인 재시도가 있습니다
              </h2>
              <Button asChild>
                <Link
                  to={
                    runPath(active.kind, active.runId, returnTo) +
                    '&retryId=' +
                    active.id
                  }
                >
                  기존 요청 확인
                </Link>
              </Button>
            </section>
          )}
          <fieldset disabled={locked} className="grid gap-6">
            <div className="md:max-w-[588px]">
              <TextField
                label="재시도 사유 *"
                disabled={
                  !!blocked ||
                  !overview.retryEnabled ||
                  !!active ||
                  storageError
                }
                value={reason}
                maxLength={500}
                onChange={(e) => {
                  setReason(e.target.value)
                  setFieldError('')
                }}
              />
              <p className="text-xs text-muted">최대 500자</p>
              <FieldError message={fieldError} />
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={
                  !!blocked ||
                  !overview.retryEnabled ||
                  !!active ||
                  storageError
                }
                variant="primary"
                onClick={() => {
                  if (!reason.trim() || reason.length > 500)
                    setFieldError('재시도 사유는 1–500자로 입력해주세요.')
                  else setConfirm(true)
                }}
              >
                재시도 확인
              </Button>
              <Button onClick={nav.cancel}>취소</Button>
            </div>
          </fieldset>
          {(blocked || notice || !overview.retryEnabled || active) && (
            <Button
              className="self-start"
              disabled={locked}
              onClick={() =>
                void perform(async (signal) => {
                  const v = await getRun(run.kind, run.id, signal),
                    o = await getRetryOverview(signal)
                  if (!signal.aborted) {
                    setRun(v)
                    setOverview(o)
                  }
                })
              }
            >
              작업 다시 조회
            </Button>
          )}
        </>
      )}
      {execution && sourceId && (
        <Button asChild>
          <Link to={runPath(run.kind, sourceId, returnTo)}>원본 실패 작업</Link>
        </Button>
      )}
      {(execution || result || unknown) && (
        <Button
          className="self-start justify-self-start"
          disabled={busy}
          onClick={nav.cancel}
        >
          {result || execution ? '실패 목록으로' : '취소'}
        </Button>
      )}
      {confirm && (
        <Dialog
          labelledBy="retry-confirm"
          className="max-w-[640px] p-8"
          onDismiss={() => {
            if (!busy) setConfirm(false)
          }}
        >
          <div className="grid gap-6">
            <h2 id="retry-confirm" className="text-lg font-bold">
              실패 작업을 다시 실행할까요?
            </h2>
            <p>
              {run.kind} · {run.id} · 시도 {run.attempt}회
            </p>
            <p>{reason}</p>
            <p>
              접수는 완료가 아닙니다. 요청 상태와 실제 실행 결과를 확인하세요.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button disabled={busy} onClick={() => setConfirm(false)}>
                취소
              </Button>
              <Button
                disabled={locked}
                variant="primary"
                onClick={() => void send()}
              >
                확인 후 저장
              </Button>
            </div>
          </div>
        </Dialog>
      )}
      {nav.dialog}
    </>
  )
}
function RunSource({ run }: { run: Run }) {
  const rows = [
    ['작업 종류 · 상태', `${run.kind} / ${run.status}`],
    ['작업 ID · 시도', `${run.id} · ${run.attempt}회`],
    [
      '시작 / 종료',
      <>
        <AdminTime value={run.startedAt} /> /{' '}
        <AdminTime value={run.finishedAt} empty="종료 시각 없음" />
      </>,
    ],
    ['작업 기준 슬롯', <AdminTime value={run.logicalSlot} empty="슬롯 없음" />],
    ['오류 코드', run.errorCode ?? '오류 코드 없음'],
    [
      '전체 / 관측 / 건너뜀 / 미처리',
      `${run.totalCount} / ${run.observedCount} / ${run.skippedCount} / ${run.pendingCount}`,
    ],
    [
      '페이지 / 후보 / 규칙 버전',
      `${run.pages} / ${run.candidates} / ${run.ruleVersion ?? '미제공'}`,
    ],
  ] as const
  return (
    <dl className="grid gap-3 bg-surface p-6">
      {rows.map(([label, value]) => (
        <div key={label} className="grid min-w-0 gap-2 break-words">
          <dt className="text-sm text-muted">{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}
