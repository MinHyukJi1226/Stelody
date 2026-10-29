import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Link,
  useBeforeUnload,
  useBlocker,
  useNavigate,
  useSearchParams,
} from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError } from '@/features/admin/format'
import { sessionOptions } from '@/features/auth/session'
import { createAdminSong, emptySongDraft } from '@/features/admin/song-create'
import {
  equal,
  toInput,
  validateDraft,
  type Draft,
} from '@/features/admin/song-draft'
import {
  getSongLabels,
  songEditPath,
  songReturnPath,
  songVideoPath,
  type EntityKind,
} from '@/features/admin/songs'
import { usePageActions, type PageAction } from '@/shared/lib/page-action-guard'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { AdminAccess, AdminApiAccess } from './admin-shared'
import { SongNav } from './song-ui'
import { SongSelector } from './song-selector'
import { SongCreateFields } from './song-create-fields'
import { useCatalogWrite } from './use-catalog-write'

type Saved = Awaited<ReturnType<typeof createAdminSong>>
export function AdminSongCreatePage() {
  const user = useAdminUser()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const returnTo = songReturnPath(params.get('returnTo'))
  const actions = usePageActions()
  const [draft, setDraft] = useState(emptySongDraft)
  const [reason, setReason] = useState('')
  const [alias, setAlias] = useState('')
  const [aliasError, setAliasError] = useState('')
  const [selector, setSelector] = useState<EntityKind | null>(null)
  const [accessError, setAccessError] = useState<unknown>(null)
  const [created, setCreated] = useState<Saved | null>(null)
  const [pendingAction, setPendingAction] = useState<PageAction | null>(null)
  const [cancelOpen, setCancelOpen] = useState(false)
  const leaving = useRef(false)
  const edit = useCatalogWrite<Saved | null>(null, {
    user,
    onAccessError: setAccessError,
  })
  const dirty =
    !created &&
    (!equal(draft, emptySongDraft()) || !!reason || !!alias || edit.uncertain)
  const locked =
    edit.busy || edit.uncertain || session.isError || !edit.verified()
  const labels = useQuery({
    queryKey: [
      ...adminKeys(user),
      'song-create-labels',
      draft.memberIds,
      draft.externalArtistIds,
      draft.workId,
    ],
    queryFn: ({ signal }) => getSongLabels(draft, signal),
    retry: false,
    gcTime: 0,
    staleTime: Infinity,
  })
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !leaving.current &&
      (dirty || edit.busy) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (dirty || edit.busy) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [dirty, edit.busy],
    ),
  )
  useEffect(
    () =>
      actions?.register((action) => {
        if (!leaving.current && (dirty || edit.busy)) {
          setPendingAction(() => action)
          return true
        }
        action()
        return false
      }),
    [actions, dirty, edit.busy],
  )
  useEffect(() => {
    if (created && blocker.state === 'blocked') blocker.reset()
  }, [created, blocker])
  function change<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((old) => ({ ...old, [key]: value }))
    edit.setErrors(
      Object.fromEntries(
        Object.entries(edit.errors).filter(
          ([field]) =>
            field !== key &&
            !field.startsWith(`${key}[`) &&
            !field.startsWith(`${key}.`),
        ),
      ),
    )
  }
  function addAlias() {
    const value = alias.trim()
    const normalize = (s: string) =>
      s
        .normalize('NFKC')
        .replace(/[\s\p{Z}]+/gu, ' ')
        .trim()
        .toLowerCase()
    if (!value) return
    if (draft.aliases.some((s) => normalize(s) === normalize(value))) {
      setAliasError('공백·대소문자가 같은 별칭이 있습니다.')
      return
    }
    if (draft.aliases.length >= 50) {
      setAliasError('별칭은 최대 50개입니다.')
      return
    }
    change('aliases', [...draft.aliases, value])
    setAlias('')
    setAliasError('')
  }
  async function save() {
    if (locked || !labels.isSuccess) return
    const errors = validateDraft(draft, reason)
    if (alias.trim())
      errors.aliases = '입력 중인 별칭을 추가하거나 비운 뒤 등록해주세요.'
    edit.setErrors(errors)
    if (Object.keys(errors).length) return
    await edit.save(
      (signal) => createAdminSong(toInput(draft, 0, reason), signal),
      (value) => {
        if (!value) return
        setCreated(value)
        setPendingAction(null)
        setCancelOpen(false)
      },
    )
  }
  function cancel() {
    if (edit.busy) return
    if (dirty) setCancelOpen(true)
    else navigate(returnTo)
  }
  function dismissExit() {
    setCancelOpen(false)
    setPendingAction(null)
    if (blocker.state === 'blocked') blocker.reset()
  }
  function leave() {
    if (edit.busy) return
    leaving.current = true
    if (blocker.state === 'blocked') blocker.proceed()
    else if (pendingAction) pendingAction()
    else navigate(returnTo)
  }
  const error = isAdminAccessError(labels.error) ? labels.error : accessError
  if (isAdminAccessError(error))
    return <AdminApiAccess error={error} user={user} />
  if (session.isError)
    return <AdminAccess state="error" onRetry={() => void session.refetch()} />
  return (
    <>
      <SongNav />
      {created ? (
        <section className="admin-panel" role="status">
          <h1 className="admin-heading">저장했습니다</h1>
          <p className="text-sm text-muted">
            곡 정보를 저장했습니다. 영상 연결과 대표 영상·공개 설정을 이어서
            완료해주세요.
          </p>
          {created.possibleDuplicateIds.length > 0 && (
            <div className="admin-notice">
              <h2 className="font-bold">비슷한 곡이 있습니다</h2>
              <p>
                기존 곡과 다른 곡인지 확인해주세요. 자동으로 합치지 않습니다.
              </p>
              <div className="flex flex-wrap gap-2">
                {created.possibleDuplicateIds.map((id, i) => (
                  <Button asChild key={id}>
                    <Link to={songEditPath(id, returnTo)}>
                      비슷한 곡 {i + 1} 확인
                    </Link>
                  </Button>
                ))}
              </div>
            </div>
          )}
          <Button asChild variant="primary">
            <Link to={songVideoPath(created.item.id, returnTo)}>
              영상 연결 계속
            </Link>
          </Button>
          <Button asChild>
            <Link to={returnTo}>원래 목록으로</Link>
          </Button>
        </section>
      ) : (
        <>
          <h1 className="admin-heading">곡 초안 등록</h1>
          <p className="text-sm text-muted">
            곡 정보 저장 → 수집 영상 연결 → 대표 영상·공개 설정 순서로
            등록합니다.
          </p>
          <section className="admin-panel">
            <h2 className="font-bold">확인해 주세요</h2>
            <p className="text-sm text-muted">
              초안 생성으로 영상이 수집되거나 자동 연결되지 않습니다.
            </p>
          </section>
          {edit.notice && (
            <p className="admin-notice text-error" role="alert">
              {edit.notice}
            </p>
          )}
          {edit.uncertain && (
            <section className="admin-panel" role="alert">
              <h2 className="text-lg font-bold">
                저장 결과를 확인할 수 없습니다
              </h2>
              <p>
                이미 초안이 등록됐을 수 있어 다시 등록하지 않습니다. 노래
                목록에서 등록 여부를 직접 확인해주세요. 같은 제목의 곡이 있어도
                이번 요청의 성공을 확정할 수 없습니다.
              </p>
              <Button
                onClick={() => setPendingAction(() => () => navigate(returnTo))}
              >
                노래 목록에서 확인
              </Button>
            </section>
          )}
          {labels.isError && (
            <section className="admin-panel" role="alert">
              <p>
                선택한 멤버·아티스트·원곡 이름을 조회하지 못했습니다. 입력은
                유지됩니다.
              </p>
              <Button onClick={() => void labels.refetch()}>다시 조회</Button>
            </section>
          )}
          {edit.busy && (
            <section className="admin-panel" role="status" aria-busy="true">
              <h2>저장 중입니다</h2>
              <p>완료될 때까지 입력·이동·중복 실행을 기다려주세요.</p>
            </section>
          )}
          <fieldset
            disabled={locked}
            className="grid min-w-0 gap-6"
            aria-busy={edit.busy}
          >
            <SongCreateFields
              draft={draft}
              change={change}
              errors={edit.errors}
              alias={alias}
              setAlias={setAlias}
              addAlias={addAlias}
              aliasError={aliasError}
              names={labels.data?.names ?? {}}
              labelState={labels.isPending ? '확인 중…' : '조회 실패'}
              select={setSelector}
              reason={reason}
              setReason={setReason}
            />
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                disabled={locked || !labels.isSuccess}
                onClick={() => void save()}
              >
                등록
              </Button>
              <Button onClick={cancel}>취소</Button>
            </div>
          </fieldset>
        </>
      )}
      {selector && !locked && (
        <SongSelector
          kind={selector}
          user={user}
          selected={
            selector === 'members'
              ? draft.memberIds
              : selector === 'artists'
                ? draft.externalArtistIds
                : draft.workId
                  ? [draft.workId]
                  : []
          }
          videos={[]}
          onApply={(ids) => {
            if (selector === 'members') change('memberIds', ids)
            else if (selector === 'artists') change('externalArtistIds', ids)
            else change('workId', ids[0] ?? null)
            setSelector(null)
          }}
          onDismiss={() => setSelector(null)}
          onAccessError={setAccessError}
        />
      )}
      {(cancelOpen || !!pendingAction || blocker.state === 'blocked') &&
        !edit.busy &&
        !created && (
          <Dialog labelledBy="create-leave-title" onDismiss={dismissExit}>
            <div className="grid gap-6">
              <h2 id="create-leave-title" className="text-lg font-bold">
                {edit.uncertain
                  ? '저장 결과를 확인하지 않고 이동할까요?'
                  : '저장하지 않고 이동할까요?'}
              </h2>
              <p className="text-sm text-muted">
                {edit.uncertain
                  ? '이미 등록됐을 수 있습니다. 입력을 버리고 결과를 확인하러 이동합니다.'
                  : '저장하지 않은 입력은 버려집니다.'}
              </p>
              <div className="flex flex-wrap gap-2">
                <Button onClick={dismissExit}>계속 작성</Button>
                <Button variant="destructive" onClick={leave}>
                  저장하지 않고 이동
                </Button>
              </div>
            </div>
          </Dialog>
        )}
    </>
  )
}
