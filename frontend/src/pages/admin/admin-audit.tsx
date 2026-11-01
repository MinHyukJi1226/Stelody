import { type AuditRecovery } from '@/features/admin/audit-recovery'
import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  auditOptions,
  auditKinds,
  auditFieldLabel,
  auditValue,
  changes,
  type Audit,
  type AuditKind,
} from '@/features/admin/audit'
import { useAdminUser } from '@/features/admin/admin-context'
import { isAdminAccessError } from '@/features/admin/format'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { AdminApiAccess, AdminPagination, AdminTime } from './admin-shared'
import { SongLoading } from './admin-songs'
import { AuditTargetPicker } from './audit-target-picker'
export type AuditTarget = { kind: AuditKind; id: string | null; name: string }
export function AuditOverlay({
  initial,
  onClose,
  recovery,
  onView,
}: {
  initial: AuditTarget | null
  recovery: AuditRecovery | null
  onView: (value: AuditRecovery) => void
  onClose: () => void
}) {
  const [target, setTarget] = useState(recovery?.target ?? initial),
    [choosing, setChoosing] = useState(
      !!recovery?.target &&
        recovery.target.kind !== 'collection-rules' &&
        recovery.target.id === null,
    )
  function chooseTarget(t: AuditTarget | null) {
    setTarget(t)
    onView({ target: t, page: 0, entryId: null })
  }
  return (
    <Dialog
      labelledBy="audit-title"
      onDismiss={onClose}
      className="fixed inset-0 m-0 h-dvh max-h-none w-screen max-w-none rounded-none border-0 bg-bg p-0"
    >
      {target && !choosing ? (
        <AuditReader
          key={`${target.kind}:${target.id}`}
          target={target}
          recovery={
            recovery?.target?.kind === target.kind &&
            recovery?.target?.id === target.id
              ? recovery
              : null
          }
          onView={onView}
          onClose={onClose}
          onChoose={() => {
            chooseTarget(null)
          }}
        />
      ) : (
        <>
          <Toolbar
            title="변경 이력 조회 대상 선택"
            onBack={onClose}
            back="관리 화면으로"
          />
          <div className="mx-auto grid max-w-[1440px] gap-6 p-5 md:px-[120px] md:py-6">
            <h2 className="text-2xl font-bold">어떤 대상의 이력을 볼까요?</h2>
            <div className="grid gap-3 md:grid-cols-2">
              {Object.entries(auditKinds).map(([kind, label]) => (
                <section key={kind} className="admin-panel gap-2 p-4">
                  <h3 className="text-lg font-bold">{label}</h3>
                  <p className="text-sm text-muted">
                    {kind === 'videos'
                      ? '곡을 먼저 선택한 뒤 연결된 영상을 선택합니다.'
                      : kind === 'artists'
                        ? '곡·원곡의 아티스트 선택·편집창에서 찾습니다.'
                        : kind === 'collection-rules'
                          ? '현재 규칙의 이력을 조회합니다. 개별 대상 선택은 필요하지 않습니다.'
                          : kind === 'reviews'
                            ? '검토 상태·분류 결과로 후보를 찾습니다.'
                            : `${label} 관리에서 대상을 선택합니다.`}
                  </p>
                  <Button
                    onClick={() => {
                      const next = {
                        kind: kind as AuditKind,
                        id: null,
                        name: label,
                      }
                      chooseTarget(next)
                      setChoosing(true)
                    }}
                  >
                    {kind === 'collection-rules' ? '규칙 선택' : '대상 선택'}
                  </Button>
                </section>
              ))}
            </div>
          </div>
        </>
      )}
      {choosing && (
        <AuditTargetPicker
          kind={target!.kind}
          onSelect={(t) => {
            chooseTarget(t)
            setChoosing(false)
          }}
          onBack={() => {
            setChoosing(false)
            chooseTarget(null)
          }}
        />
      )}
    </Dialog>
  )
}
export function Toolbar({
  title,
  context,
  onBack,
  back = '돌아가기',
}: {
  title: string
  context?: string
  onBack: () => void
  back?: string
}) {
  return (
    <header className="sticky top-0 z-10 bg-surface">
      <div className="mx-auto grid max-w-[1440px] gap-2 px-5 py-4 md:px-[120px]">
        <div className="flex items-start justify-between gap-4">
          <h1 id="audit-title" className="text-lg font-bold">
            {title}
          </h1>
          <Button onClick={onBack}>{back}</Button>
        </div>
        {context && <p className="text-sm text-muted break-words">{context}</p>}
      </div>
    </header>
  )
}
function AuditReader({
  target,
  onClose,
  onChoose,
  recovery,
  onView,
}: {
  target: AuditTarget
  recovery: AuditRecovery | null
  onView: (value: AuditRecovery) => void
  onClose: () => void
  onChoose: () => void
}) {
  const user = useAdminUser(),
    [page, setPage] = useState(recovery?.page ?? 0),
    [entryId, setEntryId] = useState<string | null>(recovery?.entryId ?? null),
    [expanded, setExpanded] = useState(false)
  const q = useQuery(auditOptions(user, target.kind, target.id, page, 20))
  const item = q.data?.items.find((x) => x.id === entryId) ?? null
  function setItem(item: Audit | null) {
    setEntryId(item?.id ?? null)
    onView({ target, page, entryId: item?.id ?? null })
  }
  const title = item
    ? expanded
      ? '긴 배열 전체 내용'
      : '변경 전후 상세'
    : '변경 이력'
  const back = () => {
    if (expanded) setExpanded(false)
    else if (item) setItem(null)
    else onClose()
  }
  let body: ReactNode
  if (isAdminAccessError(q.error))
    body = <AdminApiAccess error={q.error} user={user} />
  else if (q.isPending) body = <SongLoading />
  else if (q.isError)
    body = (
      <section className="admin-panel" role="alert">
        <h2 className="text-lg font-bold">
          {q.error instanceof ApiError && q.error.status === 404
            ? '대상을 찾을 수 없습니다'
            : '변경 이력을 조회하지 못했습니다'}
        </h2>
        <p>요청한 대상과 페이지를 유지했습니다.</p>
        <Button onClick={() => void q.refetch()}>다시 조회</Button>
        <Button onClick={onChoose}>대상 선택</Button>
      </section>
    )
  else if (item) {
    const rows = changes(item.before, item.after)
    body = (
      <>
        <div className="break-words text-sm text-muted">
          <p>
            대상: {item.targetType} · {item.targetId}
          </p>
          <p>
            작업: {item.action} · 관리자 ID {item.actorId ?? '기록 없음'}
          </p>
          <p>
            시각: <AdminTime value={item.changedAt} />
          </p>
          <p>사유: {item.reason}</p>
        </div>
        {item.before === null && (
          <section className="admin-panel" role="status">
            <h2 className="text-lg font-bold">변경 전 정보 없음</h2>
            <p>변경 전 값은 없습니다(null). 변경 후 값을 표시합니다.</p>
          </section>
        )}
        {rows.length ? (
          rows.map((row) => (
            <section key={row.key} className="admin-panel min-w-0 gap-2">
              <h2 className="text-lg font-bold break-words">
                {auditFieldLabel(row.key)}
              </h2>
              <Value label="변경 전" value={row.before} expanded={expanded} />
              <Value label="변경 후" value={row.after} expanded={expanded} />
            </section>
          ))
        ) : (
          <p>변경 전후 값이 같습니다.</p>
        )}
        <p className="text-sm text-muted">
          이력은 조회 전용입니다. 과거 값으로 일괄 복원하는 동작은 제공하지
          않습니다.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" onClick={() => setExpanded(!expanded)}>
            {expanded ? '요약 보기' : '긴 배열 펼치기'}
          </Button>
          <Button
            onClick={() => {
              setExpanded(false)
              setItem(null)
            }}
          >
            이력 목록으로
          </Button>
        </div>
      </>
    )
  } else
    body = (
      <>
        {q.data.items.length ? (
          <div className="grid gap-4">
            {q.data.items.map((entry) => (
              <div key={entry.id} className="grid gap-4">
                <button
                  className="admin-panel gap-2 break-words text-left"
                  onClick={() => setItem(entry)}
                >
                  <h2 className="text-lg font-bold">
                    <AdminTime value={entry.changedAt} /> · {entry.action}
                  </h2>
                  <p className="text-sm text-muted">
                    관리자 ID {entry.actorId ?? '기록 없음'} · {entry.reason}
                  </p>
                </button>
                <Button
                  className="justify-self-start"
                  onClick={() => setItem(entry)}
                >
                  변경 전후 보기
                </Button>
              </div>
            ))}
          </div>
        ) : (
          <section className="admin-panel" role="status">
            <h2 className="text-lg font-bold">변경 이력이 없습니다</h2>
          </section>
        )}
        <AdminPagination
          page={page}
          hasNext={q.data.hasNext}
          pending={q.isFetching}
          onPage={(next) => {
            setPage(next)
            onView({ target, page: next, entryId: null })
          }}
        />
      </>
    )
  return (
    <>
      <Toolbar
        title={title}
        context={`${auditKinds[target.kind]} · ${target.name || target.id || '현재 설정'}`}
        onBack={back}
        back={
          expanded
            ? '상세로 돌아가기'
            : item
              ? '이력 목록으로'
              : '원래 화면으로'
        }
      />
      <div className="mx-auto grid max-w-[1440px] gap-6 p-5 md:px-[120px] md:py-6">
        {body}
      </div>
    </>
  )
}
function Value({
  label,
  value,
  expanded,
}: {
  label: string
  value: Audit['before'] | undefined
  expanded: boolean
}) {
  const text = auditValue(value),
    preview =
      Array.isArray(value) && value.length > 3 && !expanded
        ? auditValue(value.slice(0, 3)) + `\n… 전체 ${value.length}개 항목`
        : text
  return (
    <div className="min-w-0 text-sm text-muted">
      <p>{label}:</p>
      <pre
        className={`${expanded ? 'max-h-96' : 'max-h-40'} overflow-auto whitespace-pre-wrap break-words font-sans [overflow-wrap:anywhere]`}
      >
        {preview}
      </pre>
    </div>
  )
}
