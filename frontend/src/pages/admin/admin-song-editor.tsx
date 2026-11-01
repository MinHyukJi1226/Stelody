import { AuditButton, AuditScope } from './audit-button'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/shared/ui/tabs'
import { usePageActions, type PageAction } from '@/shared/lib/page-action-guard'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  useBeforeUnload,
  useBlocker,
  useNavigate,
  useParams,
  useSearchParams,
  Link,
} from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError, listPage } from '@/features/admin/format'
import { sessionOptions } from '@/features/auth/session'
import {
  getAdminSong,
  getSongLabels,
  saveAdminSong,
  songOptions,
  songReturnPath,
  songVideoPath,
  type AdminSong,
} from '@/features/admin/songs'
import {
  equal,
  fields,
  reconcile,
  toDraft,
  toInput,
  validateDraft,
  type Draft,
} from '@/features/admin/song-draft'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { AdminAccess, AdminApiAccess } from './admin-shared'
import { SongLoading } from './admin-songs'
import { Hint, SongCard, SongNav } from './song-ui'
import { SongSelector, type Selection } from './song-selector'
import { SongEditorFields } from './song-editor-fields'
import { SongComparison, type Comparison } from './song-comparison'
import { WorkArtistEditor } from './work-artist-editor'
import { cleanGuard } from './use-catalog-write'

function useEditorSession() {
  return useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
}

export function AdminSongEditorPage() {
  const session = useEditorSession()
  const user = useAdminUser()
  const { songId = '' } = useParams()
  const query = useQuery(songOptions(user, songId))
  const [params] = useSearchParams()
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.isSuccess)
    return (
      <SongEditor
        key={`${user}:${songId}`}
        user={user}
        session={session}
        initial={query.data}
        returnTo={songReturnPath(params.get('returnTo'))}
      />
    )
  if (session.isError)
    return <AdminAccess state="error" onRetry={() => void session.refetch()} />
  return (
    <>
      <h1 className="admin-heading">기존 곡 수정</h1>
      <SongNav />
      <section className="admin-panel">
        {query.isPending ? (
          <SongLoading />
        ) : query.error instanceof ApiError && query.error.status === 404 ? (
          <h2>대상 곡이 없습니다.</h2>
        ) : (
          <>
            <h2>곡 정보를 조회하지 못했어요</h2>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </>
        )}
      </section>
    </>
  )
}
function SongEditor({
  user,
  session,
  initial,
  returnTo,
}: {
  user: string
  session: ReturnType<typeof useEditorSession>
  initial: AdminSong
  returnTo: string
}) {
  const pageActions = usePageActions()
  const [pendingAction, setPendingAction] = useState<PageAction | null>(null)
  const client = useQueryClient(),
    navigate = useNavigate(),
    notify = useToast()
  const [base, setBase] = useState(initial)
  const [draft, setDraft] = useState(() => toDraft(initial))
  const [reason, setReason] = useState(''),
    [tab, setTab] = useState('basic')
  const [alias, setAlias] = useState(''),
    [aliasError, setAliasError] = useState('')
  const [selector, setSelector] = useState<Selection | null>(null)
  const [workOpen, setWorkOpen] = useState(false)
  const [workGuard, setWorkGuard] = useState(cleanGuard)
  const [errors, setErrors] = useState<Record<string, string>>({}),
    [notice, setNotice] = useState('')
  const [accessError, setAccessError] = useState<unknown>(null)
  const [mode, setMode] = useState<'edit' | 'conflict' | 'unknown'>('edit')
  const [uncertain, setUncertain] = useState(false),
    [comparison, setComparison] = useState<Comparison | null>(null)
  const [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [checking, setChecking] = useState(false)
  const busyRef = useRef(false),
    leaving = useRef(false),
    mounted = useRef(false)
  const denied = useRef(false)
  const checkAbort = useRef<AbortController | null>(null)
  const saveAbort = useRef<AbortController | null>(null)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      checkAbort.current?.abort()
      saveAbort.current?.abort()
    }
  }, [])
  const current = useCallback(() => {
    const session = client.getQueryData(sessionOptions.queryKey)
    return (
      mounted.current &&
      !denied.current &&
      session?.id === user &&
      isActiveAdmin(session) &&
      !client
        .getQueryCache()
        .findAll({ queryKey: adminKeys(user) })
        .some((query) => isAdminAccessError(query.state.error))
    )
  }, [client, user])
  const accessVerified = useCallback(
    () =>
      current() &&
      client.getQueryState(sessionOptions.queryKey)?.status === 'success',
    [client, current],
  )
  const dirty =
    !equal(toDraft(base), draft) ||
    !!reason ||
    !!alias ||
    uncertain ||
    workGuard.dirty
  const allBusy = busy || workGuard.busy
  const anyUncertain = uncertain || workGuard.uncertain
  useEffect(
    () =>
      pageActions?.register((action) => {
        if (current() && (dirty || busyRef.current || workGuard.busy)) {
          setPendingAction(() => action)
          return true
        }
        action()
        return false
      }),
    [pageActions, current, dirty, workGuard.busy],
  )
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !leaving.current &&
      current() &&
      (busyRef.current || workGuard.busy || dirty) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (current() && (dirty || busyRef.current || workGuard.busy)) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [current, dirty, workGuard.busy],
    ),
  )
  const labels = useQuery({
    queryKey: [
      ...adminKeys(user),
      'song-labels',
      base.memberIds,
      base.externalArtistIds,
      base.workId,
      draft.memberIds,
      draft.externalArtistIds,
      draft.workId,
      comparison?.latest.memberIds,
      comparison?.latest.externalArtistIds,
      comparison?.latest.workId,
    ],
    queryFn: ({ signal }) =>
      getSongLabels(draft, signal, [
        base,
        ...(comparison ? [comparison.latest] : []),
      ]),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  useEffect(() => {
    if (isAdminAccessError(labels.error)) saveAbort.current?.abort()
  }, [labels.error])
  function deny(error: unknown) {
    denied.current = true
    saveAbort.current?.abort()
    setAccessError(error)
  }
  function change<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((old) => ({ ...old, [key]: value }))
    setErrors((old) => {
      const next = { ...old }
      for (const field of Object.keys(next)) {
        if (
          field === key ||
          field.startsWith(key + '[') ||
          field.startsWith(key + '.')
        )
          delete next[field]
      }
      return next
    })
  }
  function addAlias() {
    const value = alias.trim(),
      normalize = (x: string) =>
        x
          .normalize('NFKC')
          .replace(/[\s\p{Z}]+/gu, ' ')
          .trim()
          .toLowerCase()
    if (!value) return
    if (draft.aliases.length >= 50) {
      setAliasError('별칭은 최대 50개입니다.')
      return
    }
    if (draft.aliases.some((x) => normalize(x) === normalize(value))) {
      setAliasError('공백·대소문자가 같은 별칭이 있습니다.')
      return
    }
    change('aliases', [...draft.aliases, value])
    setAlias('')
    setAliasError('')
  }
  async function checkLatest() {
    if (checking || busyRef.current || !accessVerified()) return
    setChecking(true)
    setNotice('')
    const abort = new AbortController()
    checkAbort.current = abort
    try {
      const latest = await getAdminSong(base.id, abort.signal)
      if (!accessVerified()) return
      setComparison({
        latest,
        choices: reconcile(toDraft(base), draft, toDraft(latest)),
        stage: 'compare',
      })
      setConfirmed(false)
    } catch (error) {
      if (!current() || abort.signal.aborted) return
      if (isAdminAccessError(error)) deny(error)
      else
        setNotice(
          error instanceof ApiError && error.status === 404
            ? '대상 곡이 없습니다. 보존한 입력은 유지됩니다.'
            : '최신 저장값을 조회하지 못했습니다. 입력은 보존되어 있습니다.',
        )
    } finally {
      if (current()) setChecking(false)
    }
  }
  async function save(value: Draft, version: number) {
    if (busyRef.current || checking || !accessVerified()) return
    const invalid = validateDraft(value, reason)
    if (Object.keys(invalid).length) {
      setErrors(invalid)
      setNotice('입력 항목을 확인해주세요.')
      if (comparison) {
        setBase(comparison.latest)
        setDraft(value)
        setComparison(null)
        setMode('edit')
      }
      return
    }
    busyRef.current = true
    setBusy(true)
    setErrors({})
    setNotice('')
    let interrupted = false
    let unsubscribe = () => {}
    try {
      const abort = new AbortController()
      saveAbort.current = abort
      unsubscribe = client.getQueryCache().subscribe(() => {
        if (!accessVerified()) {
          interrupted = true
          abort.abort()
        }
      })
      await saveAdminSong(
        base.id,
        toInput(value, version, reason),
        abort.signal,
      )
      if (!current()) return
      if (!accessVerified()) throw new Error('SESSION_CHECK_INTERRUPTED')
      await client.invalidateQueries({
        queryKey: adminKeys(user),
        refetchType: 'none',
      })
      if (!current()) return
      if (!accessVerified()) throw new Error('SESSION_CHECK_INTERRUPTED')
      leaving.current = true
      notify('곡 정보를 저장했습니다.')
      void navigate(returnTo, { replace: true })
    } catch (error) {
      if (!current()) return
      if (isAdminAccessError(error)) {
        deny(error)
        return
      }
      if (comparison) {
        setBase(comparison.latest)
        setDraft(value)
        setComparison(null)
      }
      if (
        !interrupted &&
        error instanceof ApiError &&
        error.status === 409 &&
        error.code === 'CATALOG_VERSION_CONFLICT'
      ) {
        setMode('conflict')
        setNotice(
          '다른 관리자가 곡 정보를 변경했습니다. 입력은 보존되어 있습니다.',
        )
      } else if (
        !interrupted &&
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500
      ) {
        setMode('edit')
        setErrors(
          Object.fromEntries(
            error.fieldErrors.map((x) => [x.field, x.message]),
          ),
        )
        setNotice(
          error.status === 404
            ? '대상 곡이 없습니다. 입력은 보존되어 있습니다.'
            : error.message,
        )
      } else {
        setUncertain(true)
        setMode('unknown')
        setNotice(
          interrupted || !accessVerified()
            ? '권한 재확인 중 저장이 중단되어 결과를 확인할 수 없습니다. 이미 반영됐을 수 있으므로 현재 저장값을 확인해주세요.'
            : '응답을 받지 못했지만 변경이 이미 반영됐을 수 있습니다. 성공으로 단정하거나 같은 요청을 다시 보내지 않습니다.',
        )
      }
    } finally {
      unsubscribe()
      busyRef.current = false
      if (current()) setBusy(false)
    }
  }
  if (accessError || isAdminAccessError(labels.error))
    return <AdminApiAccess error={accessError ?? labels.error} user={user} />
  return (
    <AuditScope
      dirty={dirty || workGuard.dirty}
      busy={allBusy || checking}
      uncertain={anyUncertain}
    >
      {session.isError ? (
        <AdminAccess state="error" onRetry={() => void session.refetch()} />
      ) : (
        <>
          <h1 className="admin-heading">기존 곡 수정</h1>
          <SongNav />
          <AuditButton kind="songs" id={base.id} />
          <div>
            <Button asChild disabled={allBusy || checking}>
              <Link to={songVideoPath(base.id, returnTo)}>
                YouTube 영상 연결
              </Link>
            </Button>
          </div>
          <p className="text-xs text-muted">
            {returnTo.startsWith('/admin/registrations')
              ? '정보 보완'
              : '노래 목록'}{' '}
            ·{' '}
            {listPage(new URLSearchParams(returnTo.split('?')[1]).get('page')) +
              1}
            페이지
          </p>
          {!comparison && (mode !== 'edit' || notice) && (
            <section className="admin-panel" role="status">
              <h2 className="font-bold text-warning">
                {mode === 'unknown'
                  ? '저장 결과를 확인해야 해요'
                  : mode === 'conflict'
                    ? '다른 변경사항이 있어요'
                    : '입력 항목을 확인해주세요'}
              </h2>
              <p className="text-sm">{notice}</p>
              {mode !== 'edit' && !comparison && (
                <Button
                  variant="primary"
                  disabled={checking || busy}
                  onClick={() => void checkLatest()}
                >
                  {checking ? '최신 값 조회 중…' : '현재 저장값 확인'}
                </Button>
              )}
            </section>
          )}
          {labels.isError && (
            <div className="admin-notice" role="status">
              <p>
                연결된 항목의 이름을 조회하지 못했습니다. 저장된 선택과 입력은
                유지됩니다.
              </p>
              <Button onClick={() => void labels.refetch()}>
                이름 다시 조회
              </Button>
            </div>
          )}
          {comparison ? (
            <SongComparison
              base={base}
              draft={draft}
              value={comparison}
              onChange={setComparison}
              names={labels.data?.names ?? {}}
              ready={labels.isSuccess}
              uncertain={uncertain}
              busy={busy}
              reason={reason}
              setReason={setReason}
              confirmed={confirmed}
              setConfirmed={setConfirmed}
              save={(d, v) => void save(d, v)}
            />
          ) : (
            <>
              <Tabs value={tab} onValueChange={setTab} className="grid gap-6">
                <TabsList aria-label="곡 정보 탭">
                  {[
                    ['basic', '기본 정보'],
                    ['publish', '공개·검색'],
                    ['links', '링크·노래방'],
                  ].map(([key, name]) => (
                    <TabsTrigger
                      key={key}
                      value={key!}
                      disabled={busy || checking}
                    >
                      {name}
                    </TabsTrigger>
                  ))}
                </TabsList>
                <fieldset
                  disabled={busy || checking || mode !== 'edit'}
                  className="song-editor-fields"
                  aria-busy={busy}
                >
                  <TabsContent value={tab} className="mt-0">
                    <SongEditorFields
                      draft={draft}
                      base={base}
                      tab={tab}
                      errors={errors}
                      change={change}
                      select={setSelector}
                      editWorkArtists={() => setWorkOpen(true)}
                      names={labels.data?.names ?? {}}
                      workArtists={labels.data?.workArtists ?? []}
                      labelState={
                        labels.isError
                          ? '이름 조회 실패'
                          : labels.isPending
                            ? '이름 확인 중…'
                            : '확인됨'
                      }
                      alias={alias}
                      setAlias={(v) => {
                        setAlias(v)
                        setAliasError('')
                      }}
                      addAlias={addAlias}
                      aliasError={aliasError}
                    />
                  </TabsContent>
                  <SongCard title="변경 사유·저장">
                    <TextField
                      label="변경 사유*"
                      name="reason"
                      value={reason}
                      maxLength={500}
                      onChange={(e) => setReason(e.target.value)}
                      error={errors.reason ?? ''}
                      description="모든 수정에서 필수 · 최대 500자"
                    />
                    {Object.keys(errors).length > 0 && (
                      <div className="text-sm text-error" role="alert">
                        입력 오류:{' '}
                        {Object.entries(errors).map(([key, value]) => (
                          <p key={key}>
                            {fields.find(
                              ([field]) =>
                                key === field || key.startsWith(field + '['),
                            )?.[1] ?? '입력 항목'}
                            : {value}
                          </p>
                        ))}
                      </div>
                    )}
                    <div className="flex flex-wrap gap-2">
                      <Button
                        variant="primary"
                        disabled={!dirty || !reason.trim() || !!alias.trim()}
                        onClick={() => void save(draft, base.version)}
                      >
                        {busy ? '저장 중…' : '변경 저장'}
                      </Button>
                      <Button onClick={() => void navigate(returnTo)}>
                        취소
                      </Button>
                    </div>
                    {alias.trim() && (
                      <Hint>
                        입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.
                      </Hint>
                    )}
                    {mode === 'unknown' && (
                      <p className="text-xs text-warning">
                        수정 후 나가도 요청의 성공 여부는 확인되지 않습니다.
                      </p>
                    )}
                  </SongCard>
                </fieldset>
              </Tabs>
              {mode !== 'edit' && (
                <Button onClick={() => void navigate(returnTo)}>취소</Button>
              )}
            </>
          )}
          {selector && (
            <SongSelector
              kind={selector}
              user={user}
              videos={base.videos}
              selected={
                selector === 'members'
                  ? draft.memberIds
                  : selector === 'artists'
                    ? draft.externalArtistIds
                    : selector === 'works'
                      ? draft.workId
                        ? [draft.workId]
                        : []
                      : draft.representativeVideoId
                        ? [draft.representativeVideoId]
                        : []
              }
              onApply={(ids) => {
                if (selector === 'members') change('memberIds', ids)
                else if (selector === 'artists')
                  change('externalArtistIds', ids)
                else if (selector === 'works') change('workId', ids[0] ?? null)
                else change('representativeVideoId', ids[0] ?? null)
                setSelector(null)
              }}
              onDismiss={() => setSelector(null)}
              onAccessError={deny}
            />
          )}
        </>
      )}
      {workOpen && (
        <WorkArtistEditor
          user={user}
          workId={draft.workId}
          enabled={!session.isError}
          onAccessError={deny}
          onState={setWorkGuard}
          onSaved={() => {
            if (accessVerified()) void labels.refetch()
          }}
          onDismiss={() => {
            setWorkOpen(false)
            setWorkGuard(cleanGuard)
          }}
        />
      )}
      {(blocker.state === 'blocked' || pendingAction) && (
        <Dialog
          labelledBy="song-leave-title"
          busy={allBusy}
          onDismiss={() => {
            setPendingAction(null)
            if (blocker.state === 'blocked') blocker.reset()
          }}
        >
          <div className="grid gap-6">
            <h2 id="song-leave-title" className="text-lg font-bold">
              {allBusy
                ? '변경사항을 저장하고 있어요'
                : anyUncertain
                  ? '저장 결과를 확인하지 않고 나갈까요?'
                  : '변경사항을 버리고 이동할까요?'}
            </h2>
            <p className="text-sm text-muted">
              {allBusy
                ? '저장이 끝날 때까지 이동할 수 없습니다.'
                : anyUncertain
                  ? '요청이 이미 반영됐을 수 있습니다. 성공 여부를 확인하지 않고 이 화면을 나갑니다.'
                  : '저장하지 않은 입력은 사라집니다. 예정한 화면으로 이동합니다.'}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                onClick={() => {
                  setPendingAction(null)
                  if (blocker.state === 'blocked') blocker.reset()
                }}
                disabled={allBusy}
              >
                계속 수정
              </Button>
              <Button
                disabled={allBusy}
                onClick={() => {
                  leaving.current = true
                  if (pendingAction) {
                    const action = pendingAction
                    setPendingAction(null)
                    action()
                  } else if (blocker.state === 'blocked') blocker.proceed()
                }}
              >
                변경사항 버리고 이동
              </Button>
            </div>
          </div>
        </Dialog>
      )}
    </AuditScope>
  )
}
