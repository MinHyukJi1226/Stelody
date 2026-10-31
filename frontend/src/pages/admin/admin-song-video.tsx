import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Link,
  useBeforeUnload,
  useBlocker,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router'
import { isActiveAdmin } from '@/features/admin/api'
import { adminKeys } from '@/features/admin/queries'
import { useAdminUser } from '@/features/admin/admin-context'
import {
  isAdminAccessError,
  listPage,
  songLabel,
} from '@/features/admin/format'
import { sessionOptions } from '@/features/auth/session'
import {
  getAdminSong,
  songEditPath,
  songOptions,
  songReturnPath,
  type AdminSong,
} from '@/features/admin/songs'
import {
  attachSongVideo,
  candidateUnavailable,
  getVideoCandidate,
  videoCandidatesOptions,
  type VideoCandidate,
  type VideoKind,
} from '@/features/admin/song-videos'
import { ApiError, ApiResponseError } from '@/shared/api/errors'
import { usePageActions, type PageAction } from '@/shared/lib/page-action-guard'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import {
  AdminAccess,
  AdminApiAccess,
  AdminPagination,
  AdminTime,
} from './admin-shared'
import { SongLoading } from './admin-songs'
import { FieldError, Hint, ReadValue, SongNav } from './song-ui'
import { useCatalogWrite } from './use-catalog-write'

const kinds = [
  'OFFICIAL_MV',
  'OFFICIAL_COVER',
  'AUDIO',
  'REUPLOAD',
  'OTHER',
] as const
type Snapshot = { song: AdminSong; candidate: VideoCandidate | null }
export function AdminSongVideoPage() {
  const user = useAdminUser()
  const { songId = '' } = useParams()
  const [params] = useSearchParams()
  const query = useQuery({
    ...songOptions(user, songId),
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const candidateId = params.get('candidateId') ?? ''
  const candidateQuery = useQuery({
    queryKey: [...adminKeys(user), 'video-preset-candidate', candidateId],
    queryFn: ({ signal }) => getVideoCandidate(candidateId, signal),
    enabled: !!candidateId,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  if (candidateId && isAdminAccessError(candidateQuery.error))
    return <AdminApiAccess error={candidateQuery.error} user={user} />
  if (candidateId && candidateQuery.isError)
    return (
      <>
        <SongNav />
        <section className="admin-panel" role="alert">
          <h1 className="admin-heading">연결할 후보를 조회하지 못했습니다</h1>
          <Button onClick={() => void candidateQuery.refetch()}>
            다시 조회
          </Button>
          <Button asChild>
            <Link to={songReturnPath(params.get('returnTo'))}>
              원래 목록으로
            </Link>
          </Button>
        </section>
      </>
    )
  if (candidateId && !candidateQuery.data) return <SongLoading />
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.isSuccess && !query.isFetching)
    return (
      <VideoConnection
        key={`${user}:${songId}:${candidateId}`}
        initial={query.data}
        initialCandidate={candidateQuery.data ?? null}
        returnTo={songReturnPath(params.get('returnTo'))}
      />
    )
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">영상 연결</h1>
      <section className="admin-panel">
        {query.isPending || query.isFetching ? (
          <SongLoading />
        ) : query.error instanceof ApiError && query.error.status === 404 ? (
          <h2>대상 곡이 없습니다.</h2>
        ) : (
          <>
            <h2>곡 정보를 조회하지 못했어요</h2>
            <Button onClick={() => void query.refetch()}>다시 조회</Button>
          </>
        )}
      </section>
    </>
  )
}
function VideoConnection({
  initialCandidate,
  initial,
  returnTo,
}: {
  initial: AdminSong
  initialCandidate: VideoCandidate | null
  returnTo: string
}) {
  const user = useAdminUser()
  const client = useQueryClient()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const [params] = useSearchParams()
  const [page, setPage] = useState(() => listPage(params.get('page')))
  const navigate = useNavigate()
  const actions = usePageActions()
  const [selected, setSelected] = useState<VideoCandidate | null>(
    initialCandidate,
  )
  const [kind, setKind] = useState<VideoKind>(
    initial.type === 'COVER' ? 'OFFICIAL_COVER' : 'OFFICIAL_MV',
  )
  const [kindOpen, setKindOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [saved, setSaved] = useState(false)
  const [accessError, setAccessError] = useState<unknown>(null)
  const [pendingAction, setPendingAction] = useState<PageAction | null>(null)
  const [exit, setExit] = useState(false)
  const leaving = useRef(false)
  const edit = useCatalogWrite<Snapshot>(
    { song: initial, candidate: initialCandidate },
    { user, onAccessError: setAccessError },
  )
  const candidates = useQuery({
    ...videoCandidatesOptions(user, page),
    enabled: !selected && !saved,
  })
  const dirty = !saved && (!!selected || !!reason || edit.uncertain)
  const locked =
    edit.busy ||
    edit.uncertain ||
    edit.mode === 'conflict' ||
    session.isError ||
    !session.isSuccess ||
    !isActiveAdmin(session.data) ||
    session.data?.id !== user
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
    if (saved && blocker.state === 'blocked') blocker.reset()
  }, [saved, blocker])
  function dismissExit() {
    setExit(false)
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
  function choose(candidate: VideoCandidate) {
    if (locked) return
    setSelected(candidate)
    edit.setErrors({})
    edit.setNotice('')
  }
  function reviewConnection() {
    const errors: Record<string, string> = {}
    if (!selected) errors.candidate = '수집 후보를 선택해주세요.'
    if (!reason.trim()) errors.reason = '연결 사유를 입력해주세요.'
    if (selected && candidateUnavailable(selected))
      errors.candidate = candidateUnavailable(selected)
    edit.setErrors(errors)
    if (!locked && !Object.keys(errors).length) setConfirm(true)
  }
  async function save() {
    if (!selected || locked) return
    setConfirm(false)
    await edit.save(
      async (signal) => {
        const video = await attachSongVideo(
          selected,
          {
            version: selected.version,
            songId: initial.id,
            songVersion: edit.base.song.version,
            kind,
            reason: reason.trim(),
          },
          signal,
        )
        const song = await getAdminSong(initial.id, signal)
        if (
          song.version <= edit.base.song.version ||
          !song.videos.some(
            (x) => x.id === video.id && x.youtubeId === selected.youtubeId,
          )
        )
          throw new ApiResponseError()
        return { song, candidate: selected }
      },
      ({ song }) => {
        client.setQueryData(songOptions(user, initial.id).queryKey, song)
        setSaved(true)
        setPendingAction(null)
        setExit(false)
      },
    )
  }
  async function check() {
    if (!selected) return
    await edit.check(async (signal) => {
      const [song, candidate] = await Promise.all([
        getAdminSong(initial.id, signal),
        getVideoCandidate(selected.id, signal).catch((error: unknown) => {
          if (error instanceof ApiError && error.status === 404) return null
          throw error
        }),
      ])
      signal.throwIfAborted()
      if (edit.verified())
        client.setQueryData(songOptions(user, initial.id).queryKey, song)
      return { song, candidate }
    })
  }
  const error = isAdminAccessError(candidates.error)
    ? candidates.error
    : accessError
  if (isAdminAccessError(error))
    return <AdminApiAccess error={error} user={user} />
  if (session.isError)
    return <AdminAccess state="error" onRetry={() => void session.refetch()} />
  const latest = edit.latest
  const existing =
    selected &&
    (latest?.song ?? edit.base.song).videos.find(
      (x) => x.youtubeId === selected.youtubeId,
    )
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">
        {saved
          ? '영상을 연결했습니다'
          : selected
            ? '영상 연결'
            : '연결할 수집 후보'}
      </h1>
      <p className="text-sm text-muted">대상 곡 · {edit.base.song.title}</p>
      {saved ? (
        <section className="admin-panel" role="status">
          <h2 className="text-lg font-bold">
            대표 영상·공개 설정을 이어서 확인해주세요
          </h2>
          <p>
            영상 연결을 저장했습니다. 곡 수정에서 대표 영상을 지정하고 공개
            설정으로 저장하면 공개 조건을 확인합니다.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button asChild variant="primary">
              <Link to={songEditPath(initial.id, returnTo)}>
                대표 영상·공개 설정
              </Link>
            </Button>
            <Button asChild>
              <Link to={returnTo}>원래 목록으로</Link>
            </Button>
          </div>
        </section>
      ) : (
        <>
          <section className="admin-panel">
            <h2 className="font-bold">확인해 주세요</h2>
            <p className="text-sm text-muted">
              검토 대기(PENDING)이며 수집 사용 중인 공식 채널의 최신 공개 후보만
              연결할 수 있습니다. 관측 30일 초과·미래 공개·정보 부족 후보는 다시
              확인해야 합니다.
            </p>
          </section>
          <section className="admin-panel">
            <h2 className="font-bold">확인해 주세요</h2>
            <p className="text-sm text-muted">
              영상 연결과 대표 영상 선택은 별도입니다. 연결 후 곡 수정에서 대표
              영상과 공개 설정을 저장하세요.
            </p>
          </section>
          {edit.notice && (
            <section className="admin-panel" role="alert">
              <p className="text-error">{edit.notice}</p>
              {edit.mode !== 'conflict' && !edit.uncertain && (
                <Button
                  disabled={edit.busy}
                  onClick={() => {
                    setSelected(null)
                    edit.setNotice('')
                    void candidates.refetch()
                  }}
                >
                  후보 목록 다시 조회
                </Button>
              )}
            </section>
          )}
          {(edit.mode === 'conflict' || edit.uncertain) && (
            <section className="admin-panel" role="status">
              <h2 className="text-lg font-bold">
                {edit.uncertain
                  ? '연결 결과를 확인해야 해요'
                  : '검토 중 곡 또는 후보가 변경되었습니다'}
              </h2>
              <p>
                {edit.uncertain
                  ? '이미 연결됐을 수 있어 자동으로 재전송하지 않습니다. 최신 곡과 후보 정보를 확인해주세요.'
                  : '입력을 보존했습니다. 최신 곡·후보 버전을 확인한 뒤 다시 검토해주세요.'}
              </p>
              <ReadValue label="기존 조회값">
                {edit.base.song.title} · 곡 버전 {edit.base.song.version} / 후보
                버전 {selected?.version}
              </ReadValue>
              <ReadValue label="내 입력">
                {selected?.title ?? selected?.youtubeId} · {songLabel(kind)} ·{' '}
                {reason}
              </ReadValue>
              {latest && (
                <>
                  <ReadValue label="최신 저장값">
                    {latest.song.title} · 곡 버전 {latest.song.version} /{' '}
                    {latest.candidate
                      ? `후보 버전 ${latest.candidate.version} · ${latest.candidate.status}`
                      : '후보 없음'}
                    <br />
                    {existing
                      ? '선택한 영상이 이 곡에 연결되어 있습니다.'
                      : '선택한 영상이 현재 이 곡에 없습니다.'}
                  </ReadValue>
                  {edit.uncertain && (
                    <Hint>
                      현재 값이 내 입력과 같아도 이 요청의 성공을 증명하지
                      않습니다.
                    </Hint>
                  )}
                </>
              )}
              <div className="flex flex-wrap gap-2">
                <Button disabled={edit.busy} onClick={() => void check()}>
                  {edit.busy ? '조회 중…' : '최신 저장값 조회'}
                </Button>
                {latest &&
                  !edit.uncertain &&
                  !existing &&
                  latest.candidate &&
                  !candidateUnavailable(latest.candidate) && (
                    <Button
                      variant="primary"
                      disabled={edit.busy}
                      onClick={() => {
                        setSelected(latest.candidate)
                        edit.review()
                      }}
                    >
                      입력 재확인
                    </Button>
                  )}
                {latest && existing && (
                  <Button asChild disabled={edit.busy}>
                    <Link to={songEditPath(initial.id, returnTo)}>
                      연결된 영상·공개 설정 확인
                    </Link>
                  </Button>
                )}
              </div>
            </section>
          )}
          {edit.busy && (
            <section className="admin-panel" role="status" aria-busy="true">
              <h2>처리 중입니다</h2>
              <p>입력·이동·중복 제출을 잠시 막고 있습니다.</p>
            </section>
          )}
          {selected ? (
            <fieldset disabled={locked} className="grid min-w-0 gap-6">
              <div className="song-create-grid">
                <div className="song-create-relation">
                  <ReadValue label="연결할 곡">
                    {edit.base.song.title}
                  </ReadValue>
                </div>
                <div className="song-create-relation">
                  <ReadValue label="수집 후보">
                    {selected.title ?? selected.youtubeId} ·{' '}
                    {selected.channelName ?? '채널명 없음'}
                    <br />
                    관측 <AdminTime value={selected.observedAt} />
                  </ReadValue>
                </div>
                <ReadValue label="YouTube 영상">
                  <a
                    className="text-brand"
                    href={`https://www.youtube.com/watch?v=${selected.youtubeId}`}
                    target="_blank"
                    rel="noreferrer"
                  >{`https://www.youtube.com/watch?v=${selected.youtubeId}`}</a>
                </ReadValue>
                <div className="grid gap-2">
                  <span className="text-sm font-medium">영상 종류*</span>
                  <Button
                    className="min-h-14 justify-start border border-line font-normal"
                    onClick={() => setKindOpen(true)}
                  >
                    {songLabel(kind)}
                  </Button>
                  <FieldError message={edit.errors.kind} />
                </div>
                <TextField
                  label="연결 사유*"
                  value={reason}
                  maxLength={500}
                  onChange={(e) => {
                    setReason(e.target.value)
                    edit.setErrors({ ...edit.errors, reason: '' })
                  }}
                  error={edit.errors.reason ?? ''}
                />
              </div>
              <FieldError message={edit.errors.candidate} />
              {Object.entries(edit.errors)
                .filter(
                  ([key]) => !['reason', 'kind', 'candidate'].includes(key),
                )
                .map(([key, value]) => (
                  <FieldError key={key} message={value} />
                ))}
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => setSelected(null)}>
                  다른 수집 후보 선택
                </Button>
                <Button variant="primary" onClick={reviewConnection}>
                  연결 확인
                </Button>
              </div>
            </fieldset>
          ) : (
            <>
              {candidates.isPending ? (
                <section className="admin-panel" role="status">
                  <SongLoading />
                </section>
              ) : candidates.isError ? (
                <section className="admin-panel" role="alert">
                  <h2>수집 후보를 조회하지 못했습니다</h2>
                  <Button onClick={() => void candidates.refetch()}>
                    다시 조회
                  </Button>
                </section>
              ) : (
                <>
                  {!candidates.data.items.length && (
                    <section className="admin-panel" role="status">
                      <h2>목록이 비어 있습니다</h2>
                      <p>연결할 검토 대기 후보가 없습니다.</p>
                    </section>
                  )}
                  {candidates.data.items.map((candidate) => (
                    <section key={candidate.id} className="admin-panel">
                      <h2 className="font-bold">
                        {candidate.title ?? '제목 없음'}
                      </h2>
                      <p className="text-sm text-muted">
                        {candidate.channelName ?? '채널명 없음'} ·{' '}
                        {songLabel(candidate.availability)} · 관측{' '}
                        <AdminTime value={candidate.observedAt} />
                      </p>
                      {candidateUnavailable(candidate) && (
                        <p className="text-sm text-warning">
                          {candidateUnavailable(candidate)}
                        </p>
                      )}
                      <Button
                        disabled={
                          locked ||
                          !!candidateUnavailable(candidate) ||
                          candidates.isFetching
                        }
                        onClick={() => choose(candidate)}
                      >
                        이 후보 선택
                      </Button>
                    </section>
                  ))}
                  <AdminPagination
                    page={page}
                    hasNext={candidates.data.hasNext}
                    pending={candidates.isFetching || locked}
                    onPage={(value) => {
                      setPage(value)
                    }}
                  />
                </>
              )}
            </>
          )}
          <div>
            <Button
              disabled={edit.busy}
              onClick={() => {
                if (dirty) setExit(true)
                else navigate(returnTo)
              }}
            >
              취소
            </Button>
          </div>
        </>
      )}
      {kindOpen && !locked && (
        <Dialog
          labelledBy="video-kind-title"
          onDismiss={() => setKindOpen(false)}
        >
          <div className="grid gap-6">
            <h2 id="video-kind-title" className="text-lg font-bold">
              영상 종류
            </h2>
            <Hint>연결한 곡의 대표 영상 기준과 함께 확인하세요.</Hint>
            {kinds.map((value) => (
              <Button
                key={value}
                aria-pressed={kind === value}
                variant={kind === value ? 'primary' : 'secondary'}
                onClick={() => {
                  setKind(value)
                  setKindOpen(false)
                }}
              >
                {songLabel(value)}
              </Button>
            ))}
            <Button onClick={() => setKindOpen(false)}>취소</Button>
          </div>
        </Dialog>
      )}
      {confirm && !locked && (
        <Dialog
          labelledBy="video-confirm-title"
          onDismiss={() => setConfirm(false)}
        >
          <div className="grid gap-6">
            <h2 id="video-confirm-title" className="text-lg font-bold">
              선택한 영상을 연결할까요?
            </h2>
            <p className="text-sm text-muted">
              {edit.base.song.title} / {selected?.title} / {songLabel(kind)}
              <br />
              {reason}
            </p>
            <p className="text-sm text-muted">
              입력한 변경 내용과 사유를 확인하세요. 충돌 시 전체 입력을
              보존합니다.
            </p>
            <Button variant="primary" onClick={() => void save()}>
              확인 후 저장
            </Button>
            <Button onClick={() => setConfirm(false)}>취소</Button>
          </div>
        </Dialog>
      )}
      {(exit || pendingAction || blocker.state === 'blocked') &&
        !edit.busy &&
        !saved && (
          <Dialog labelledBy="video-exit-title" onDismiss={dismissExit}>
            <div className="grid gap-6">
              <h2 id="video-exit-title" className="text-lg font-bold">
                {edit.uncertain
                  ? '연결 결과를 확인하지 않고 이동할까요?'
                  : '저장하지 않고 이동할까요?'}
              </h2>
              <p className="text-sm text-muted">
                {edit.uncertain
                  ? '이미 영상이 연결됐을 수 있습니다. 요청의 성공 여부를 확인하지 않고 나갑니다.'
                  : '작성 중인 입력은 버립니다. 이미 생성한 곡과 저장된 영상 연결은 유지됩니다.'}
              </p>
              <Button onClick={dismissExit}>계속 작성</Button>
              <Button variant="destructive" onClick={leave}>
                변경 버리고 이동
              </Button>
            </div>
          </Dialog>
        )}
    </>
  )
}
