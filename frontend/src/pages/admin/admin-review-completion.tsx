import {
  readPendingReview,
  storePendingReview,
  clearPendingReview,
  type PendingReview,
} from '@/features/admin/review-receipt'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useBeforeUnload, useBlocker, useNavigate } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { adminPaths, isActiveAdmin } from '@/features/admin/api'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError } from '@/features/admin/format'
import { sessionOptions } from '@/features/auth/session'
import {
  getReviewCompletion,
  completeReview,
  reviewRequest,
  reviewFieldErrors,
  type ReviewPreview,
  type ReviewRequest,
  type ReviewResult,
} from '@/features/admin/review-completion'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { usePageActions, type PageAction } from '@/shared/lib/page-action-guard'
import {
  AdminAccess,
  AdminApiAccess,
  AdminPagination,
  AdminTime,
} from './admin-shared'
import { SongCard, SongNav, Hint } from './song-ui'
import { SongLoading } from './admin-songs'

export function AdminReviewsPage() {
  const user = useAdminUser()
  const [recovery, setRecovery] = useState(() => readPendingReview(user))
  const query = useQuery({
    queryKey: [...adminKeys(user), 'review-completion-preview'],
    queryFn: ({ signal }) => getReviewCompletion(signal),
    retry: false,
    enabled: recovery.state === 'none',
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  })
  if (recovery.state === 'pending')
    return (
      <ReviewBatch
        user={user}
        preview={recovery.pending.preview}
        recovered={recovery.pending}
        onRestart={() => {
          setRecovery({ state: 'none' })
          void query.refetch()
        }}
      />
    )
  if (recovery.state === 'unavailable' || recovery.state === 'invalid')
    return (
      <>
        <h1 className="admin-heading">신규 후보 등록·정리</h1>
        <SongNav />
        <SongCard title="처리 요청을 확인할 수 없어요">
          <p className="text-sm text-muted">
            {recovery.state === 'invalid'
              ? '이 탭에 보관된 요청을 읽을 수 없습니다. 기존 요청의 완료 여부를 추정하거나 새 등록 요청을 보내지 않습니다.'
              : '브라우저의 탭 저장 공간에 접근할 수 없습니다. 저장 설정을 확인해주세요.'}
          </p>
          <Button onClick={() => setRecovery(readPendingReview(user))}>
            다시 확인
          </Button>
          <Button asChild>
            <Link to={adminPaths.inbox}>작업함으로</Link>
          </Button>
        </SongCard>
      </>
    )
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.isSuccess && !query.isFetching)
    return (
      <ReviewBatch
        key={`${user}:${query.dataUpdatedAt}`}
        user={user}
        preview={query.data}
        onRestart={() => void query.refetch()}
      />
    )
  return (
    <>
      <h1 className="admin-heading">신규 후보 등록·정리</h1>
      <SongNav />
      <section className="admin-panel">
        {query.isFetching || query.isPending ? (
          <SongLoading />
        ) : (
          <>
            <h2 className="font-bold">검토 대상을 불러오지 못했어요</h2>
            <p className="text-sm text-muted">
              현재 후보 수나 선택 상태를 확인할 수 없습니다.
            </p>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </>
        )}
      </section>
    </>
  )
}
type Frozen = { request: ReviewRequest; titles: string[] }
function ReviewBatch({
  user,
  preview,
  onRestart,
  recovered,
}: {
  recovered?: PendingReview
  user: string
  preview: ReviewPreview
  onRestart: () => void
}) {
  const client = useQueryClient(),
    navigate = useNavigate(),
    actions = usePageActions()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const [selected, setSelected] = useState<string[]>(
    () => recovered?.request.selected.map((item) => item.id) ?? [],
  )
  const [page, setPage] = useState(0)
  const [mode, setMode] = useState<'edit' | 'conflict' | 'unknown' | 'success'>(
    recovered ? 'unknown' : 'edit',
  )
  const [errors, setErrors] = useState<Record<string, string[]>>({})
  const [classificationFailed, setClassificationFailed] = useState(false)
  const [notice, setNotice] = useState(''),
    [accessError, setAccessError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false),
    [confirm, setConfirm] = useState(false),
    [deleteConfirmed, setDeleteConfirmed] = useState(false)
  const [exit, setExit] = useState(false),
    [pendingAction, setPendingAction] = useState<PageAction | null>(null)
  const [frozen, setFrozen] = useState<Frozen | null>(recovered ?? null),
    [result, setResult] = useState<ReviewResult | null>(null)
  const mounted = useRef(false),
    denied = useRef(false),
    busyRef = useRef(false),
    leaving = useRef(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      controller.current?.abort()
    }
  }, [])
  const current = useCallback(() => {
    const actor = client.getQueryData(sessionOptions.queryKey)
    return (
      mounted.current &&
      !denied.current &&
      actor?.id === user &&
      isActiveAdmin(actor) &&
      !client
        .getQueryCache()
        .findAll({ queryKey: adminKeys(user) })
        .some((q) => isAdminAccessError(q.state.error))
    )
  }, [client, user])
  const verified = useCallback(
    () =>
      current() &&
      client.getQueryState(sessionOptions.queryKey)?.status === 'success',
    [client, current],
  )
  const deny = useCallback((error: unknown) => {
    denied.current = true
    controller.current?.abort()
    setAccessError(error)
  }, [])
  const dirty =
    mode !== 'success' && (selected.length > 0 || mode === 'unknown')
  useEffect(
    () =>
      actions?.register((action) => {
        if (current() && busyRef.current) return true
        if (current() && dirty && !leaving.current) {
          setPendingAction(() => action)
          return true
        }
        action()
        return false
      }),
    [actions, current, dirty],
  )
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !leaving.current &&
      current() &&
      (dirty || busyRef.current) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (current() && (dirty || busyRef.current)) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [current, dirty],
    ),
  )
  const rows = preview.items.slice(page * 20, (page + 1) * 20)
  const ready = selected.length > 0
  function choose(ids: string[]) {
    if (ids.length > 100) {
      setNotice('등록할 후보는 최대 100개까지 선택할 수 있습니다.')
      return
    }
    setNotice('')
    setErrors({})
    setSelected(ids)
  }
  async function send(retry = false) {
    if (
      busyRef.current ||
      !verified() ||
      (!retry && (!ready || !deleteConfirmed))
    )
      return
    let attempt = frozen
    try {
      if (!retry)
        attempt = {
          request: reviewRequest(preview, selected, crypto.randomUUID()),
          titles: preview.items
            .filter((item) => selected.includes(item.id))
            .map((item) => item.title ?? '제목 정보 없음'),
        }
      if (!attempt) return
      attempt = structuredClone(attempt)
      storePendingReview({ user, ...attempt, preview })
    } catch (error) {
      setConfirm(false)
      setNotice(
        error instanceof Error ? error.message : '선택한 후보를 확인해주세요.',
      )
      return
    }
    busyRef.current = true
    setBusy(true)
    setConfirm(false)
    setNotice('')
    setFrozen(attempt)
    const abort = new AbortController()
    controller.current = abort
    let interrupted = false
    const unsubscribe = client.getQueryCache().subscribe(() => {
      if (!verified()) {
        interrupted = true
        abort.abort()
      }
    })
    try {
      const response = await completeReview(attempt.request, abort.signal)
      if (!current()) return
      if (interrupted || !verified()) {
        setMode('unknown')
        return
      }
      clearPendingReview(user)
      setResult(response)
      setMode('success')
      // Invalidate counts and song lists without refetching or replacing the fixed candidate set.
      void client.invalidateQueries({
        queryKey: adminKeys(user),
        refetchType: 'none',
      })
    } catch (error) {
      if (!current()) return
      if (interrupted) {
        setMode('unknown')
        return
      }
      if (isAdminAccessError(error)) {
        deny(error)
        return
      }
      if (error instanceof ApiError && error.status === 400) {
        clearPendingReview(user)
        const fields = reviewFieldErrors(error, attempt.request)
        setErrors(fields.candidates)
        setNotice(
          [
            '선택한 후보를 다시 확인해주세요. 등록·삭제는 모두 취소됐습니다.',
            error.message,
            ...fields.general,
          ].join(' '),
        )
        setMode('edit')
        setFrozen(null)
        setDeleteConfirmed(false)
      } else if (error instanceof ApiError && error.status === 409) {
        clearPendingReview(user)
        const fields = reviewFieldErrors(error, attempt.request)
        setErrors(fields.candidates)
        setClassificationFailed(
          error.code === 'CANDIDATE_CLASSIFICATION_REQUIRED',
        )
        setNotice([error.message, ...fields.general].join(' '))
        setMode('conflict')
        setFrozen(null)
      } else setMode('unknown')
    } finally {
      unsubscribe()
      if (current()) {
        busyRef.current = false
        setBusy(false)
      }
    }
  }
  function dismissLeave() {
    setExit(false)
    setPendingAction(null)
    if (blocker.state === 'blocked') blocker.reset()
  }
  function leave() {
    if (busyRef.current) return
    leaving.current = true
    if (blocker.state === 'blocked') blocker.proceed()
    else if (pendingAction) {
      const action = pendingAction
      setPendingAction(null)
      action()
    } else navigate(adminPaths.inbox)
  }
  const showLeave = exit || !!pendingAction || blocker.state === 'blocked'
  if (isAdminAccessError(accessError))
    return <AdminApiAccess error={accessError} user={user} />
  return (
    <>
      {session.isError ? (
        <AdminAccess state="error" onRetry={() => void session.refetch()} />
      ) : (
        <>
          <h1 className="admin-heading">신규 후보 등록·정리</h1>
          <SongNav />
          {notice && (
            <p className="admin-panel text-error" role="alert">
              {notice}
            </p>
          )}
          {busy ? (
            <section className="admin-panel" role="status" aria-busy="true">
              <h2 className="font-bold">
                {mode === 'unknown'
                  ? '같은 요청 결과를 확인하고 있어요'
                  : '선택한 곡을 등록하고 후보를 정리하고 있어요'}
              </h2>
              <p className="text-sm text-muted">
                완료될 때까지 입력·이동·중복 실행을 기다려주세요.
              </p>
            </section>
          ) : mode === 'unknown' ? (
            <SongCard title="처리 결과를 확인해야 해요">
              <p className="text-sm text-muted">
                응답을 받지 못했습니다. 이미 등록·삭제됐을 수 있으므로 새 작업을
                시작하거나 내용을 바꾸지 마세요.
              </p>
              <Button variant="primary" onClick={() => void send(true)}>
                같은 요청 결과 확인
              </Button>
              <Button onClick={() => setExit(true)}>작업함으로</Button>
            </SongCard>
          ) : mode === 'conflict' ? (
            <SongCard
              title={
                classificationFailed
                  ? '등록 정보를 자동으로 구성하지 못했어요'
                  : '검토 대상이 변경됐어요'
              }
            >
              <p className="text-sm text-muted">
                등록·삭제가 모두 취소됐습니다. 목록을 다시 조회하고 등록할
                후보를 확인해주세요.
              </p>
              {Object.entries(errors).map(([id, messages]) => (
                <div key={id} className="text-sm text-error" role="alert">
                  <p className="font-bold">
                    {preview.items.find((item) => item.id === id)?.title ??
                      '제목 정보 없음'}
                  </p>
                  {messages.map((message, index) => (
                    <p key={index}>{message}</p>
                  ))}
                </div>
              ))}
              <Button variant="primary" onClick={onRestart}>
                목록 다시 조회
              </Button>
              <Button onClick={() => setExit(true)}>작업함으로</Button>
            </SongCard>
          ) : mode === 'success' && result ? (
            <SongCard
              title={
                result.replayed
                  ? '이전 요청의 완료를 확인했어요'
                  : '등록·정리를 완료했어요'
              }
            >
              <p>
                등록 {result.registeredSongs.length}곡 ·{' '}
                {result.replayed
                  ? `추가 삭제 ${result.deletedCount}건`
                  : `삭제 ${result.deletedCount}건`}
              </p>
              <p className="text-sm text-muted">
                새로 유입된 후보·기존 등록 곡·완료·보류·제외 후보와 수집 지점은
                유지됩니다.
              </p>
              <p className="text-sm text-muted">
                곡은 초안으로 등록됩니다. 노래 목록에서 누락 정보를 보완하고
                공개 설정을 수정할 수 있습니다.
              </p>
              {result.replayed && (
                <p className="text-sm text-muted">
                  최초 처리의 삭제 건수는 이번 응답에서 다시 제공되지 않습니다.
                </p>
              )}
              <Button variant="primary" asChild>
                <Link to={adminPaths.songs}>등록된 노래 보기</Link>
              </Button>
              <Button onClick={onRestart}>새 검토 시작</Button>
              <Button asChild>
                <Link to={adminPaths.inbox}>작업함으로</Link>
              </Button>
            </SongCard>
          ) : (
            <>
              <SongCard title="등록할 후보 선택">
                {preview.items.length ? (
                  <>
                    <p className="text-sm text-brand">
                      이번 검토 {preview.items.length}건 · 선택{' '}
                      {selected.length}건 · 미선택{' '}
                      {preview.items.length - selected.length}건
                    </p>
                    <p className="text-sm text-muted">
                      처음 확인한 대상 안에서 선택을 유지합니다. 이후 새로
                      들어온 후보는 이번 등록·삭제 대상이 아닙니다.
                    </p>
                    {preview.hasMore && (
                      <p className="text-sm text-warning">
                        최대 10,000건까지만 불러왔습니다. 추가 후보는 이번 삭제
                        대상이 아니며 다음 검토에서 처리합니다.
                      </p>
                    )}
                    <div className="flex flex-wrap gap-2">
                      <Button
                        onClick={() =>
                          choose([
                            ...new Set([
                              ...selected,
                              ...rows.map((item) => item.id),
                            ]),
                          ])
                        }
                      >
                        현재 페이지 선택
                      </Button>
                      <Button
                        disabled={!selected.length}
                        onClick={() => choose([])}
                      >
                        선택 해제
                      </Button>
                    </div>
                    <Hint>
                      {page + 1} / {Math.ceil(preview.items.length / 20)}페이지
                      · 페이지를 바꿔도 선택 유지
                    </Hint>
                    <div className="grid gap-4">
                      {rows.map((item) => {
                        const checked = selected.includes(item.id)
                        return (
                          <article key={item.id} className="review-candidate">
                            <label
                              className={`review-candidate-choice ${checked ? 'review-candidate-selected' : ''}`}
                            >
                              <input
                                type="checkbox"
                                checked={checked}
                                onChange={() =>
                                  choose(
                                    checked
                                      ? selected.filter((id) => id !== item.id)
                                      : [...selected, item.id],
                                  )
                                }
                              />
                              <span>{item.title ?? '제목 정보 없음'}</span>
                            </label>
                            <p className="text-sm text-muted">
                              채널 {item.channelName ?? '채널 정보 없음'} · 최초
                              발견 <AdminTime value={item.firstSeenAt} />
                            </p>
                            <Hint>
                              등록 정보는 수집 자료를 바탕으로 서버에서
                              처리합니다.
                            </Hint>
                            {(errors[item.id] ?? []).map((message, i) => (
                              <p
                                className="text-sm text-error"
                                key={i}
                                role="alert"
                              >
                                {message}
                              </p>
                            ))}
                          </article>
                        )
                      })}
                    </div>
                    <AdminPagination
                      page={page}
                      hasNext={(page + 1) * 20 < preview.items.length}
                      pending={false}
                      onPage={setPage}
                    />
                  </>
                ) : (
                  <p role="status">신규 검토 대상이 없습니다.</p>
                )}
              </SongCard>
              <section className="admin-panel">
                <p className="text-sm text-muted">
                  선택한 후보는 서버에서 정보를 구성해 초안으로 등록합니다.
                  유형이 미확정인 후보는 기본 커버로 등록합니다. 원곡 정보는
                  확인 가능한 범위에서 보완하며, 누락 정보와 공개 설정은 노래
                  목록에서 수정할 수 있습니다.
                </p>
                <p className="text-sm font-bold">선택 {selected.length}건</p>
                <p className="text-sm text-warning">
                  완료하면 이 검토 대상의 미선택 후보{' '}
                  {preview.items.length - selected.length}건과 종속 검토 이력이
                  영구 삭제됩니다.
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="primary"
                    disabled={!ready}
                    onClick={() => {
                      setDeleteConfirmed(false)
                      setConfirm(true)
                    }}
                  >
                    등록·정리 확인
                  </Button>
                  <Button onClick={() => setExit(true)}>검토 종료</Button>
                </div>
              </section>
            </>
          )}
        </>
      )}
      {confirm && !busy && !session.isError && (
        <Dialog
          labelledBy="review-confirm-title"
          onDismiss={() => setConfirm(false)}
        >
          <div className="grid gap-6">
            <h2 id="review-confirm-title" className="text-lg font-bold">
              선택 곡을 등록하고 미선택 후보를 삭제할까요?
            </h2>
            <p className="text-sm font-bold">
              이번 검토 {preview.items.length}건 · 등록 {selected.length}곡 ·
              삭제 예정 {preview.items.length - selected.length}건
            </p>
            <p className="text-sm break-words">
              등록 ·{' '}
              {preview.items
                .filter((item) => selected.includes(item.id))
                .map((item) => item.title ?? '제목 정보 없음')
                .join(' / ')}
            </p>
            <p className="text-sm text-muted">
              서버가 등록 정보를 구성하며, 모든 곡은 초안으로 저장됩니다. 유형이
              미확정인 후보는 기본 커버로 등록합니다. 유형 충돌 등으로 등록할 수
              없는 후보가 있으면 전체 처리가 취소됩니다.
            </p>
            <div className="admin-notice">
              <h3 className="font-bold">미선택 후보는 복구할 수 없습니다</h3>
              <p className="text-sm text-muted">
                현재 페이지 밖을 포함한 이 검토 대상 중 미선택 후보{' '}
                {preview.items.length - selected.length}건과 종속 검토 이력을
                영구 삭제합니다.
              </p>
            </div>
            <p className="text-sm text-muted">
              새로 유입된 후보·기존 등록 곡·완료·보류·제외 후보와 수집 지점은
              유지됩니다.
            </p>
            <Hint>
              한 곡이라도 실패하거나 대상이 변경되면 등록·삭제가 모두
              취소됩니다.
            </Hint>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={deleteConfirmed}
                onChange={(e) => setDeleteConfirmed(e.target.checked)}
              />
              영구 삭제 범위를 확인했습니다
            </label>
            <Button onClick={() => setConfirm(false)}>취소</Button>
            <Button
              variant="destructive"
              disabled={!deleteConfirmed || !ready}
              onClick={() => void send()}
            >
              {selected.length}곡 등록·미선택{' '}
              {preview.items.length - selected.length}건 영구 삭제
            </Button>
          </div>
        </Dialog>
      )}
      {showLeave && !busy && (
        <Dialog labelledBy="review-exit-title" onDismiss={dismissLeave}>
          <div className="grid gap-6">
            <h2 id="review-exit-title" className="text-lg font-bold">
              검토를 종료할까요?
            </h2>
            <p className="text-sm text-muted">
              {mode === 'unknown'
                ? '등록·삭제의 성공 여부를 확인하지 않고 나갑니다.'
                : '선택한 후보는 저장되지 않습니다. 아직 등록·삭제를 실행하지 않습니다.'}
            </p>
            <Button onClick={dismissLeave}>계속 검토</Button>
            <Button variant="destructive" onClick={leave}>
              검토 종료하고 이동
            </Button>
          </div>
        </Dialog>
      )}
    </>
  )
}
