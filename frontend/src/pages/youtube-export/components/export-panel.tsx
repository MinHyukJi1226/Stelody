import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { ExportJob } from '@/features/youtube-export/api'
import { Button } from '@/shared/ui/button'
import type { ExportView } from '../export-view'

const copy: Record<
  ExportView,
  { label: string; title: string; description: string; tone?: string }
> = {
  prepare: {
    label: '내보내기 전',
    title: 'YouTube로 내보낼 준비가 되었어요',
    description:
      '새 비공개 YouTube 플레이리스트로 복사됩니다.\n이용 불가 곡은 건너뜁니다.',
  },
  unconnected: {
    label: 'YouTube 미연결',
    title: 'YouTube 계정 연결이 필요해요',
    description:
      'YouTube 연결 관리에서 추가 동의를 완료한 뒤 내보내기를 시작해 주세요.',
  },
  reconnect: {
    label: '재연결 필요',
    title: 'YouTube에 다시 연결해 주세요',
    description:
      '접근 권한이 만료되었거나 유효하지 않습니다. 연결 관리에서 다시 동의해 주세요.',
    tone: 'text-warning',
  },
  disabled: {
    label: '기능 비활성',
    title: '지금은 내보내기를 사용할 수 없어요',
    description: '내보내기 기능이 현재 제공되지 않습니다.',
  },
  disconnecting: {
    label: '연결 해제 처리 중',
    title: '연결 해제가 끝날 때까지 기다려 주세요',
    description: 'YouTube 연결을 해제하고 있어 내보내기를 시작할 수 없습니다.',
  },
  nosongs: {
    label: '내보낼 곡 없음',
    title: '내보낼 수 있는 곡이 없어요',
    description:
      '이용 가능한 곡을 목록에 추가해 주세요. 이용 불가 곡은 건너뜁니다.',
    tone: 'text-warning',
  },
  accepting: {
    label: '접수 중',
    title: '내보내기 요청을 보내고 있어요',
    description:
      '아직 접수가 확정되지 않았습니다. 중복 요청을 막기 위해 잠시 기다려 주세요.',
  },
  accepted: {
    label: '접수 완료 · 복사 전',
    title: '요청을 접수했어요',
    description:
      '접수 성공은 내보내기 완료가 아닙니다. 아직 곡 복사가 완료되지 않았습니다.',
  },
  pending: {
    label: '대기 중',
    title: '내보내기 차례를 기다리고 있어요',
    description:
      '작업이 대기 중입니다. 복사가 시작되면 진행 정보가 표시됩니다.',
  },
  running: {
    label: '진행 중',
    title: 'YouTube에 곡을 복사하고 있어요',
    description: '복사가 끝날 때까지 진행 정보를 확인해 주세요.',
  },
  loading: {
    label: '상태 조회 중',
    title: '작업 상태를 확인하고 있어요',
    description: '조회가 완료되면 최신 진행 정보가 표시됩니다.',
  },
  queryerror: {
    label: '상태 조회 오류',
    title: '최신 작업 상태를 불러오지 못했어요',
    description:
      '아래 수치는 마지막으로 확인한 정보입니다. 조회 오류만으로 내보내기가 실패한 것은 아닙니다.',
    tone: 'text-warning',
  },
  retrying: {
    label: '같은 작업 재시도 중',
    title: '기존 작업을 이어서 재시도하고 있어요',
    description:
      '새 내보내기 작업을 만들지 않습니다. 중복 실행을 막기 위해 잠시 기다려 주세요.',
  },
  cancelling: {
    label: '취소 중',
    title: '작업 취소를 요청하고 있어요',
    description:
      '취소가 아직 완료되지 않았습니다. 이미 복사된 YouTube 목록과 곡은 삭제하지 않습니다.',
  },
  completed: {
    label: '완료',
    title: 'YouTube 내보내기가 완료됐어요',
    description: '전체 곡의 복사가 완료되었습니다.',
    tone: 'text-green',
  },
  partial: {
    label: '일부 곡 건너뛰고 완료',
    title: '이용 불가 곡을 건너뛰고 완료했어요',
    description: '',
    tone: 'text-green',
  },
  failed: {
    label: '실패',
    title: 'YouTube에 곡을 복사하지 못했어요',
    description:
      'YouTube 요청 처리 중 오류가 발생했습니다. 기존 작업을 이어서 다시 시도하거나 취소할 수 있습니다.',
    tone: 'text-error',
  },
  uncertain: {
    label: '결과 미확정',
    title: '일부 곡의 반영 결과를 확인하지 못했어요',
    description:
      '확인되지 않은 곡이 이미 YouTube에 반영됐을 수 있습니다. 결과를 확인한 뒤 기존 작업을 이어서 재시도하거나 취소해 주세요.',
    tone: 'text-warning',
  },
  cancelled: {
    label: '취소 완료',
    title: '내보내기 작업을 취소했어요',
    description:
      '이미 생성된 YouTube 목록과 복사된 곡은 유지됩니다. 취소는 복사된 목록이나 곡을 삭제하지 않습니다.',
  },
  quota: {
    label: '실패 · 할당량 초과',
    title: 'YouTube 할당량을 초과했어요',
    description:
      '지금은 요청을 처리할 수 없습니다. 잠시 후 같은 작업을 다시 시도하거나 취소해 주세요.',
    tone: 'text-warning',
  },
  channel: {
    label: '실패 · YouTube 채널 없음',
    title: 'YouTube 채널이 필요해요',
    description:
      '연결한 계정에 YouTube 채널이 없습니다. YouTube에서 채널을 만든 뒤 같은 작업을 다시 시도해 주세요.',
    tone: 'text-warning',
  },
  permission: {
    label: '실패 · 재연결 필요',
    title: 'YouTube 접근 권한을 다시 확인해 주세요',
    description:
      '연결 관리에서 다시 동의한 뒤 이 작업으로 돌아와 같은 작업을 재시도해 주세요.',
    tone: 'text-warning',
  },
  sourcechanged: {
    label: '원본 목록 변경',
    title: '원본 플레이리스트가 변경됐어요',
    description: '최신 목록을 다시 불러와 내보낼 곡을 확인한 뒤 접수해 주세요.',
    tone: 'text-warning',
  },
}
const preflight = new Set<ExportView>([
  'prepare',
  'unconnected',
  'reconnect',
  'disabled',
  'disconnecting',
  'nosongs',
  'sourcechanged',
])

function ExportCounts({ job }: { job: ExportJob | undefined }) {
  const values = [
    ['전체 곡', job?.totalCount],
    ['복사 완료', job?.copiedCount],
    ['건너뜀', job?.skippedCount],
    ['결과 미확정', job?.remainingCount],
  ] as const
  return (
    <dl
      className="grid w-full min-w-0 grid-cols-2 gap-2"
      aria-label="내보내기 곡 수"
      aria-busy={!job}
    >
      {values.map(([label, value]) => (
        <div
          key={label}
          className="flex min-w-0 flex-col gap-2 rounded-xl bg-bg p-3"
        >
          <dt className="break-words text-xs leading-[1.5] text-muted">
            {label}
          </dt>
          <dd
            className={`text-2xl font-bold leading-[1.5] ${label === '결과 미확정' ? 'text-warning' : ''}`}
          >
            {value === undefined ? (
              <>
                <span className="sr-only">확인 중</span>
                <span
                  aria-hidden="true"
                  className="block h-6 w-16 max-w-full rounded-lg bg-line"
                />
              </>
            ) : (
              `${value}곡`
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}

export function ExportPanel({
  view,
  name,
  total,
  available,
  job,
  busy,
  connectionPath,
  notice,
  canRetry,
  canCancel,
  onStart,
  onRetry,
  onCancel,
  onRead,
  onReload,
}: {
  view: ExportView
  name: string | undefined
  total: number | undefined
  available: number | undefined
  job: ExportJob | undefined
  busy: boolean
  connectionPath: string
  notice?: ReactNode
  canRetry: boolean
  canCancel: boolean
  onStart: () => void
  onRetry: () => void
  onCancel: () => void
  onRead: () => void
  onReload: () => void
}) {
  const text = copy[view]
  const isPreflight = preflight.has(view)
  const waiting = ['accepting', 'loading', 'retrying', 'cancelling'].includes(
    view,
  )
  const retryable = [
    'failed',
    'uncertain',
    'quota',
    'channel',
    'permission',
  ].includes(view)
  const resultLink = job?.youtubeUrl ? (
    <Button asChild>
      <a href={job.youtubeUrl} target="_blank" rel="noopener noreferrer">
        {view === 'uncertain' ? '결과 확인' : 'YouTube 목록 열기'}
      </a>
    </Button>
  ) : null
  return (
    <section
      aria-label="YouTube 내보내기 상태"
      aria-busy={busy || waiting}
      className="flex w-full max-w-[760px] min-w-0 flex-col gap-4 rounded-2xl bg-surface p-6 text-sm leading-[1.5]"
    >
      <div className="flex min-w-0 flex-col gap-1">
        {name ? (
          <h2 className="break-words text-lg font-bold leading-[1.5]">
            {name}
          </h2>
        ) : (
          <div
            aria-label="플레이리스트 확인 중"
            className="h-[27px] w-40 max-w-full rounded-lg bg-line"
          />
        )}
        <p className="text-muted">
          사이트 플레이리스트 ·{' '}
          {job
            ? `총 ${job.totalCount}곡`
            : total === undefined
              ? '확인 중'
              : `총 ${total}곡`}
        </p>
      </div>
      {notice}
      <p role="status" className={`font-medium ${text.tone ?? 'text-brand'}`}>
        {text.label}
      </p>
      <h3 className="break-words text-lg font-bold leading-[1.5]">
        {text.title}
      </h3>
      <p className="whitespace-pre-line text-muted">
        {view === 'partial' && job
          ? `복사 완료 ${job.copiedCount}곡 · 건너뜀 ${job.skippedCount}곡. 건너뛴 곡은 YouTube 목록에 포함되지 않습니다.`
          : view === 'queryerror' && !job
            ? '조회가 완료되지 않아 진행 정보를 확인할 수 없습니다. 상태를 다시 확인해 주세요.'
            : text.description}
      </p>
      {isPreflight ? (
        <div className="flex flex-col gap-2 rounded-xl bg-bg p-3">
          <p className="font-medium">
            복사 대상 {available === undefined ? '확인 중' : `${available}곡`}
          </p>
          <p className="text-muted">
            건너뛸 예정{' '}
            {total === undefined || available === undefined
              ? '확인 중'
              : `${total - available}곡`}
          </p>
        </div>
      ) : (
        <ExportCounts
          job={view === 'accepting' || view === 'loading' ? undefined : job}
        />
      )}
      {isPreflight && view !== 'prepare' && (
        <p className="whitespace-pre-line text-muted">
          {copy.prepare.description}
        </p>
      )}
      {view !== 'uncertain' && resultLink && <div>{resultLink}</div>}
      <div className="flex flex-wrap gap-2">
        {isPreflight && (view === 'unconnected' || view === 'reconnect') ? (
          <Button asChild variant="primary">
            <Link to={connectionPath}>YouTube 연결 관리</Link>
          </Button>
        ) : isPreflight && view !== 'sourcechanged' ? (
          <Button
            variant="primary"
            disabled={busy || view !== 'prepare'}
            onClick={onStart}
          >
            내보내기 시작
          </Button>
        ) : null}
        {view === 'disconnecting' && (
          <Button asChild>
            <Link to={connectionPath}>YouTube 연결 관리</Link>
          </Button>
        )}
        {view === 'sourcechanged' && (
          <Button variant="primary" disabled={busy} onClick={onReload}>
            최신 목록 확인
          </Button>
        )}
        {waiting && (
          <Button
            variant={
              view === 'accepting' || view === 'retrying'
                ? 'primary'
                : 'secondary'
            }
            disabled
          >
            {view === 'accepting'
              ? '접수 중…'
              : view === 'retrying'
                ? '재시도 중…'
                : view === 'cancelling'
                  ? '취소 중…'
                  : '상태 확인 중…'}
          </Button>
        )}
        {view === 'queryerror' && (
          <Button variant="primary" disabled={busy} onClick={onRead}>
            상태 다시 조회
          </Button>
        )}
        {view === 'uncertain' && resultLink}
        {view === 'permission' && (
          <Button asChild>
            <Link to={connectionPath}>YouTube 연결 관리</Link>
          </Button>
        )}
        {retryable && (
          <Button
            variant="primary"
            disabled={busy || !canRetry}
            onClick={onRetry}
          >
            같은 작업 재시도
          </Button>
        )}
        {canCancel && !waiting && (
          <Button disabled={busy} onClick={onCancel}>
            취소
          </Button>
        )}
      </div>
      {retryable && (
        <p className="text-xs leading-[1.5] text-muted">
          기존 작업을 이어서 재시도합니다.
        </p>
      )}
      {canCancel && !waiting && (
        <p className="text-xs leading-[1.5] text-muted">
          취소해도 이미 생성된 YouTube 목록과 복사된 곡은 유지됩니다.
        </p>
      )}
    </section>
  )
}
