import { VideoTargetPicker } from './admin-management-menu'
import { songVideoPath, songEditPath } from '@/features/admin/songs'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams, useNavigate } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError, songLabel } from '@/features/admin/format'
import {
  reviewItemOptions,
  reviewReturnPath,
  writeReview,
  validateReview,
  reviewStatusLabels,
  type ReviewChange,
} from '@/features/admin/review-items'
import {
  candidateUnavailable,
  getVideoCandidate,
  type VideoCandidate,
} from '@/features/admin/song-videos'
import { sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import {
  AdminAccess,
  AdminApiAccess,
  AdminTime,
  AdminTable,
} from './admin-shared'
import { SongNav, FieldError } from './song-ui'
import { SongLoading } from './admin-songs'
import { useCatalogWrite } from './use-catalog-write'
import { useWorkNavigation } from './use-work-navigation'
export function AdminReviewItemPage() {
  const user = useAdminUser(),
    { reviewId = '' } = useParams(),
    [params] = useSearchParams(),
    q = useQuery(reviewItemOptions(user, reviewId)),
    returnTo = reviewReturnPath(params.get('returnTo'))
  if (isAdminAccessError(q.error))
    return <AdminApiAccess error={q.error} user={user} />
  if (q.data)
    return <ReviewEditor key={reviewId} initial={q.data} returnTo={returnTo} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">수집 후보 상세</h1>
      {q.isPending ? (
        <SongLoading />
      ) : (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">
            {q.error instanceof ApiError && q.error.status === 404
              ? '검토 후보를 찾을 수 없습니다'
              : '후보 정보를 조회하지 못했습니다'}
          </h2>
          {!(q.error instanceof ApiError && q.error.status === 404) && (
            <Button onClick={() => void q.refetch()}>다시 조회</Button>
          )}
          <Button asChild>
            <Link to={returnTo}>원래 목록으로</Link>
          </Button>
        </section>
      )}
    </>
  )
}
function ReviewEditor({
  initial,
  returnTo,
}: {
  initial: VideoCandidate
  returnTo: string
}) {
  const navigate = useNavigate()
  const [chooseSong, setChooseSong] = useState(false)
  const user = useAdminUser(),
    notify = useToast(),
    session = useQuery({
      ...sessionOptions,
      refetchOnMount: false,
      retryOnMount: false,
      refetchOnWindowFocus: false,
    })
  const [accessError, setAccessError] = useState<unknown>(null),
    edit = useCatalogWrite(initial, {
      user,
      onAccessError: setAccessError,
      conflictCodes: ['REVIEW_VERSION_CONFLICT', 'REVIEW_ALREADY_REGISTERED'],
    })
  const [status, setStatus] = useState(
      initial.status === 'IGNORED' ? 'IGNORED' : 'PENDING',
    ),
    [reason, setReason] = useState(''),
    [choice, setChoice] = useState(false)
  const base = edit.base,
    registered = base.status === 'REGISTERED',
    dirty =
      !!reason ||
      status !== (base.status === 'IGNORED' ? 'IGNORED' : 'PENDING') ||
      edit.uncertain
  const nav = useWorkNavigation(
    dirty,
    edit.busy,
    edit.uncertain,
    returnTo,
    '저장하지 않은 검토 상태와 변경 사유는 버려집니다.',
  )
  const locked =
    edit.busy ||
    !session.isSuccess ||
    !isActiveAdmin(session.data) ||
    session.data?.id !== user ||
    edit.mode !== 'edit' ||
    registered
  function confirm() {
    if (locked) return
    const errors = validateReview(status, reason)
    edit.setErrors(errors)
    if (!Object.keys(errors).length) edit.setMode('confirm')
  }
  if (isAdminAccessError(accessError))
    return (
      <>
        <AdminApiAccess error={accessError} user={user} />
        {nav.dialog}
      </>
    )
  if (session.isError)
    return (
      <>
        <AdminAccess state="error" onRetry={() => void session.refetch()} />
        {nav.dialog}
      </>
    )
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">수집 후보 상세</h1>
      <section className="admin-panel gap-2">
        <h2 className="font-bold">확인해 주세요</h2>
        <p className="text-sm text-muted">
          개별 변경은 검토 대기와 제외만 지원합니다. 등록 완료 영상은 곡
          관리에서 수정합니다.
        </p>
      </section>
      <h2 className="text-lg font-bold">수집 원본 · 읽기 전용</h2>
      <section className="admin-panel">
        <SourceValue label="수집 영상 제목">
          {base.title ?? '제목 없음'}
        </SourceValue>
        <SourceValue label="채널">
          {base.channelName ?? '채널 정보 없음'}
        </SourceValue>
        <SourceValue label="공개 상태 · 관측">
          {songLabel(base.availability)} ·{' '}
          <AdminTime value={base.observedAt} empty="관측 기록 없음" />
        </SourceValue>
        <SourceValue label="YouTube 공개일">
          <AdminTime value={base.publishedAt} empty="공개일 없음" />
        </SourceValue>
        <SourceValue label="최초 발견">
          <AdminTime value={base.firstSeenAt} />
        </SourceValue>
        <SourceValue label="분류 결과 / 제안">
          {base.disposition} / {base.suggestedType}
        </SourceValue>
        <SourceValue label="판정 근거 · 규칙 버전">
          {base.decisionReason} · {base.ruleVersion}
        </SourceValue>
        <SourceValue label="후보 버전 · 근거 만료">
          v{base.version} · {base.sourceExpired ? '만료' : '만료 아님'}
        </SourceValue>
        <SourceValue label="현재 검토 상태">
          {reviewStatusLabels[base.status] ?? base.status}
        </SourceValue>
        <SourceValue label="이전 검토 사유">
          {base.reviewNote ?? '검토 사유 없음'}
        </SourceValue>
      </section>
      {base.sourceExpired && (
        <section className="admin-notice">
          <h2 className="font-bold">수집 근거가 오래되었습니다</h2>
          <p className="text-sm text-muted">
            관측 근거 만료와 검토 상태는 별개입니다. 최신성 확인 전 영상 연결은
            하지 않습니다.
          </p>
        </section>
      )}
      {registered && (
        <section className="admin-panel" role="status">
          <h2 className="text-lg font-bold">이미 등록된 후보입니다</h2>
          <p>
            검토 상태를 변경할 수 없습니다. 연결된 곡에서 영상 정보를
            확인하세요.
          </p>
        </section>
      )}
      {edit.notice && (
        <p className="admin-notice text-error" role="alert">
          {edit.notice}
        </p>
      )}
      {!!Object.keys(edit.errors).length && (
        <div className="admin-notice" role="alert">
          <h2 className="font-bold">입력 내용을 확인해 주세요</h2>
          {Object.entries(edit.errors).map(([k, v]) => (
            <p key={k} className="text-error">
              {v}
            </p>
          ))}
        </div>
      )}
      {['conflict', 'unknown', 'checked'].includes(edit.mode) && (
        <section className="admin-panel" role="status">
          <h2 className="text-lg font-bold">
            {edit.mode === 'conflict'
              ? '검토 중 후보가 변경되었습니다'
              : edit.latest
                ? '최신 내용을 확인했습니다'
                : '저장 결과를 확인할 수 없습니다'}
          </h2>
          <p className="text-sm text-muted">
            최초 값·내 입력·최신 값을 비교한 뒤 명시적으로 다시 확인해주세요.
            현재 값이 같아도 이 요청의 성공을 증명하지 않습니다. 자동 재전송하지
            않습니다.
          </p>
          {edit.latest && (
            <AdminTable
              headers={['항목', '최초 조회값', '내 입력', '최신 저장값']}
              widths={[16, 28, 28, 28]}
              empty=""
              rows={[
                {
                  key: 'status',
                  cells: [
                    '검토 상태',
                    reviewStatusLabels[base.status] ?? base.status,
                    reviewStatusLabels[status],
                    reviewStatusLabels[edit.latest.status] ??
                      edit.latest.status,
                  ],
                },
                {
                  key: 'reason',
                  cells: [
                    '변경 사유',
                    base.reviewNote ?? '없음',
                    reason,
                    edit.latest.reviewNote ?? '없음',
                  ],
                },
                {
                  key: 'disposition',
                  cells: [
                    '분류 결과 / 제안',
                    base.disposition + ' / ' + base.suggestedType,
                    '수집 원본 · 읽기 전용',
                    edit.latest.disposition + ' / ' + edit.latest.suggestedType,
                  ],
                },
                {
                  key: 'expired',
                  cells: [
                    '근거 만료',
                    base.sourceExpired ? '만료' : '만료 아님',
                    '수집 원본 · 읽기 전용',
                    edit.latest.sourceExpired ? '만료' : '만료 아님',
                  ],
                },
              ]}
            />
          )}
          <div className="flex flex-wrap gap-2">
            {edit.latest ? (
              <Button
                variant="primary"
                disabled={edit.busy}
                onClick={edit.review}
              >
                입력 재확인
              </Button>
            ) : (
              <Button
                variant="primary"
                disabled={edit.busy}
                onClick={() =>
                  void edit.check((signal) =>
                    getVideoCandidate(base.id, signal),
                  )
                }
              >
                최신 저장값 조회
              </Button>
            )}
            <Button disabled={edit.busy} onClick={nav.cancel}>
              원래 목록으로
            </Button>
          </div>
        </section>
      )}
      {!registered && (
        <fieldset
          disabled={locked}
          className="grid gap-6"
          aria-busy={edit.busy}
        >
          <div className="song-editor-grid">
            <div className="grid gap-2">
              <span className="text-sm font-medium">검토 상태*</span>
              <Button
                className="min-h-14 justify-start border border-line font-normal"
                aria-label="검토 상태 선택"
                onClick={() => setChoice(true)}
              >
                {reviewStatusLabels[status]}
              </Button>
              <FieldError message={edit.errors.status} />
            </div>
            <TextField
              label="변경 사유*"
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              error={edit.errors.reason ?? ''}
              description="최대 500자 · 모든 변경에서 필수"
            />
          </div>
          <Button
            className="justify-self-start"
            variant="primary"
            onClick={confirm}
          >
            변경 저장
          </Button>
        </fieldset>
      )}
      <div className="flex flex-wrap gap-2">
        {registered ? (
          <Button
            disabled={edit.busy || !base.registeredSongId}
            onClick={() => {
              if (base.registeredSongId)
                navigate(songEditPath(base.registeredSongId, returnTo))
            }}
          >
            기존 곡 수정
          </Button>
        ) : (
          <Button
            variant="primary"
            disabled={locked || !!candidateUnavailable(base)}
            onClick={() => setChooseSong(true)}
          >
            이 후보를 기존 곡에 연결
          </Button>
        )}
        <Button disabled>후보 변경 이력 · 준비 중</Button>
        <Button disabled={edit.busy} onClick={nav.cancel}>
          취소
        </Button>
      </div>
      {chooseSong && (
        <VideoTargetPicker
          onDismiss={() => setChooseSong(false)}
          onApply={(songId) => {
            setChooseSong(false)
            navigate(
              songVideoPath(songId, returnTo) +
                '&candidateId=' +
                encodeURIComponent(base.id),
            )
          }}
        />
      )}
      {choice && (
        <Dialog
          labelledBy="review-status-choice"
          onDismiss={() => setChoice(false)}
        >
          <h2 id="review-status-choice" className="mb-6 text-lg font-bold">
            검토 상태
          </h2>
          <div className="grid gap-3">
            {['PENDING', 'IGNORED'].map((v) => (
              <Button
                key={v}
                aria-pressed={status === v}
                onClick={() => {
                  setStatus(v)
                  setChoice(false)
                  edit.setErrors({})
                }}
              >
                {reviewStatusLabels[v]}
              </Button>
            ))}
            <Button onClick={() => setChoice(false)}>취소</Button>
          </div>
        </Dialog>
      )}
      {edit.mode === 'confirm' && (
        <Dialog
          labelledBy="review-confirm"
          busy={edit.busy}
          onDismiss={() => {
            if (!edit.busy) edit.setMode('edit')
          }}
        >
          <h2 id="review-confirm" className="mb-6 text-lg font-bold">
            {edit.busy ? '저장 중입니다' : '검토 상태를 변경할까요?'}
          </h2>
          <p className="mb-6 text-sm text-muted">
            {reviewStatusLabels[status]} · {reason}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="primary"
              disabled={edit.busy}
              onClick={() =>
                void edit.save(
                  (signal) =>
                    writeReview(
                      base.id,
                      {
                        version: base.version,
                        status: status as ReviewChange['status'],
                        reason: reason.trim(),
                      },
                      signal,
                    ),
                  () => {
                    notify('검토 상태를 저장했습니다.')
                    nav.go(returnTo)
                  },
                )
              }
            >
              확인 후 저장
            </Button>
            <Button disabled={edit.busy} onClick={() => edit.setMode('edit')}>
              취소
            </Button>
          </div>
        </Dialog>
      )}
      {nav.dialog}
    </>
  )
}

function SourceValue({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div className="grid gap-2">
      <p className="text-xs text-muted">{label}</p>
      <div className="text-sm whitespace-pre-wrap break-words">{children}</div>
    </div>
  )
}
