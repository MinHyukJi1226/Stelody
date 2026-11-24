import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { ExportJob } from '@/features/youtube-export/api'
import { Button } from '@/shared/ui/button'
import type { ExportView } from '../export-view'

const copy: Record<ExportView, { message: string; tone?: string }> = {
  prepare: {
    message: 'YouTube로 내보낼 준비가 되었어요',
  },
  unconnected: {
    message: 'YouTube 계정 연결이 필요해요',
  },
  reconnect: {
    message: 'YouTube에 다시 연결해 주세요',
    tone: 'text-warning',
  },
  disabled: {
    message: '지금은 내보내기를 사용할 수 없어요',
  },
  disconnecting: {
    message: '연결 해제가 끝날 때까지 기다려 주세요',
  },
  nosongs: {
    message: '내보낼 수 있는 곡이 없어요',
    tone: 'text-warning',
  },
  accepting: {
    message: '내보내기 요청을 보내고 있어요',
  },
  accepted: {
    message: '요청을 접수했어요',
  },
  pending: {
    message: '내보내기 차례를 기다리고 있어요',
  },
  running: {
    message: 'YouTube에 곡을 복사하고 있어요',
  },
  loading: {
    message: '작업 상태를 확인하고 있어요',
  },
  queryerror: {
    message: '최신 작업 상태를 불러오지 못했어요',
    tone: 'text-warning',
  },
  retrying: {
    message: '기존 작업을 이어서 재시도하고 있어요',
  },
  cancelling: {
    message: '작업 취소를 요청하고 있어요',
  },
  completed: {
    message: 'YouTube 내보내기가 완료됐어요',
    tone: 'text-green',
  },
  partial: {
    message: '이용 불가 곡을 건너뛰고 완료했어요',
    tone: 'text-green',
  },
  failed: {
    message: 'YouTube에 곡을 복사하지 못했어요',
    tone: 'text-error',
  },
  uncertain: {
    message: '일부 곡의 반영 결과를 확인하지 못했어요',
    tone: 'text-warning',
  },
  cancelled: {
    message: '내보내기 작업을 취소했어요',
  },
  quota: {
    message: 'YouTube 할당량을 초과했어요',
    tone: 'text-warning',
  },
  channel: {
    message: 'YouTube 채널이 필요해요',
    tone: 'text-warning',
  },
  permission: {
    message: 'YouTube 접근 권한을 다시 확인해 주세요',
    tone: 'text-warning',
  },
  sourcechanged: {
    message: '원본 플레이리스트가 변경됐어요',
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

export function ExportPanel({
  view,
  name,
  total,
  preview,
  job,
  busy,
  connectionPath,
  notice,
  canRetry,
  canCancel,
  needsConnection,
  onStart,
  onRetry,
  onCancel,
  onRead,
  onReload,
}: {
  view: ExportView
  name: string | undefined
  total: number | undefined
  preview?: ReactNode
  job: ExportJob | undefined
  busy: boolean
  connectionPath: string
  notice?: ReactNode
  canRetry: boolean
  canCancel: boolean
  needsConnection: boolean
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
      className="flex w-full min-w-0 flex-col items-start gap-4 text-sm leading-[1.5]"
    >
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-1 gap-y-2">
        {name ? (
          <h2 className="min-w-0 break-words text-lg leading-[1.5] md:text-xl">
            {name}
          </h2>
        ) : (
          <div
            aria-label="플레이리스트 확인 중"
            className="h-[27px] w-40 max-w-full rounded-lg bg-line"
          />
        )}
        <p className="text-muted">
          ·{' '}
          {job
            ? `총 ${job.totalCount}곡`
            : total === undefined
              ? '확인 중'
              : `총 ${total}곡`}
        </p>
      </div>
      {notice}
      {isPreflight && <p>비공개 YouTube 플레이리스트로 내보냅니다</p>}
      {view !== 'prepare' && (
        <p role="status" className={`break-words ${text.tone ?? 'text-ink'}`}>
          {text.message}
        </p>
      )}
      {isPreflight && preview}
      {view !== 'uncertain' && resultLink && <div>{resultLink}</div>}
      <div className="mt-3 flex flex-wrap gap-2">
        {isPreflight && (view === 'unconnected' || view === 'reconnect') ? (
          <Button asChild variant="primary">
            <Link to={connectionPath}>YouTube 연결 관리</Link>
          </Button>
        ) : isPreflight && view !== 'sourcechanged' ? (
          <Button
            variant="primary"
            className="min-h-11 px-4 py-3"
            disabled={busy || view !== 'prepare'}
            onClick={onStart}
          >
            내보내기
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
        {(view === 'permission' || (retryable && needsConnection)) && (
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
    </section>
  )
}
