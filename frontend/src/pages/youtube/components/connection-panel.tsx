import type { ReactNode } from 'react'
import type { YouTubeConnection } from '@/features/youtube/api'
import { Button } from '@/shared/ui/button'

type PanelState = YouTubeConnection['status'] | 'LOADING' | 'ERROR' | 'STARTING'
const content = {
  DISCONNECTED: {
    label: '미연결',
    tone: 'text-brand',
    title: 'YouTube 계정을 연결하세요',
    description:
      'Google 동의 화면에서 YouTube 접근 권한에 추가 동의가 필요합니다.',
    detail:
      'Stelody 로그인과 별도로 진행됩니다. 연결만으로 내보내기가 실행되지는 않습니다.',
  },
  CONNECTED: {
    label: '연결 완료',
    tone: 'text-green',
    title: 'YouTube가 연결되어 있어요',
    description: '플레이리스트 내보내기에 필요한 계정 연결이 완료되었습니다.',
    detail:
      '연결을 해제해도 사이트 플레이리스트와 이미 생성된 YouTube 목록은 유지됩니다.',
  },
  RECONNECT_REQUIRED: {
    label: '재연결 필요',
    tone: 'text-warning',
    title: 'YouTube에 다시 연결해 주세요',
    description:
      '접근 권한이 만료되었거나 더 이상 유효하지 않습니다. Google에서 다시 동의해 주세요.',
  },
  REVOKING: {
    label: '해제 처리 중',
    tone: 'text-brand',
    title: 'YouTube 연결을 해제하고 있어요',
    description: '처리가 끝날 때까지 잠시 기다려 주세요.',
    detail: '사이트의 내보내기 작업 기록을 삭제하고 있습니다.',
  },
  DISABLED: {
    label: '현재 사용 불가',
    tone: 'text-muted',
    title: '지금은 YouTube 연결을 사용할 수 없어요',
    description: '기능이 다시 제공되면 연결할 수 있습니다.',
  },
  ERROR: {
    label: '상태 조회 오류',
    tone: 'text-error',
    title: '연결 상태를 불러오지 못했어요',
    description: '잠시 후 다시 시도해 주세요.',
  },
  STARTING: {
    label: '인증 시작 중',
    tone: 'text-brand',
    title: 'Google 인증을 여는 중이에요',
    description: '인증을 마치면 이 연결 관리 화면으로 돌아옵니다.',
  },
}

export function ConnectionPanel({
  state,
  busy,
  notice,
  failed,
  onConnect,
  onDisconnect,
  onRetry,
}: {
  state: PanelState
  busy: boolean
  notice?: ReactNode
  failed: boolean
  onConnect: () => void
  onDisconnect: () => void
  onRetry: () => void
}) {
  if (state === 'LOADING')
    return (
      <section
        role="status"
        aria-label="YouTube 연결 상태 확인 중"
        aria-busy="true"
        className="flex w-full max-w-[640px] flex-col gap-4 rounded-2xl bg-surface p-6 text-sm leading-[1.5]"
      >
        <p className="font-medium text-brand">연결 상태 확인 중</p>
        <div
          aria-hidden="true"
          className="h-4 w-[220px] max-w-full rounded-lg bg-line"
        />
        <div
          aria-hidden="true"
          className="h-4 w-[500px] max-w-full rounded-lg bg-line"
        />
        <div
          aria-hidden="true"
          className="h-4 w-[420px] max-w-full rounded-lg bg-line"
        />
        <div aria-hidden="true" className="h-12 w-[140px] rounded-lg bg-line" />
        <p className="text-muted">연결 상태를 확인하고 있습니다.</p>
      </section>
    )
  const text = content[state]
  return (
    <section
      aria-label="YouTube 연결 상태"
      aria-busy={busy}
      className="flex w-full max-w-[640px] flex-col gap-4 rounded-2xl bg-surface p-6 text-sm leading-[1.5]"
    >
      {notice}
      <p
        className={`font-medium ${failed && state === 'DISCONNECTED' ? 'text-error' : text.tone}`}
      >
        {text.label}
      </p>
      <h2 className="break-words text-lg font-bold leading-[1.5]">
        {text.title}
      </h2>
      <p className="text-muted">{text.description}</p>
      {'detail' in text && <p className="text-muted">{text.detail}</p>}
      <div className="flex flex-wrap gap-2">
        {state === 'CONNECTED' ? (
          <Button variant="destructive" disabled={busy} onClick={onDisconnect}>
            연결 해제
          </Button>
        ) : state === 'ERROR' ? (
          <Button variant="primary" disabled={busy} onClick={onRetry}>
            다시 시도
          </Button>
        ) : (
          <Button
            variant="primary"
            disabled={
              busy ||
              state === 'DISABLED' ||
              state === 'REVOKING' ||
              state === 'STARTING'
            }
            onClick={onConnect}
          >
            {state === 'RECONNECT_REQUIRED' ||
            (failed && state === 'DISCONNECTED')
              ? '다시 연결'
              : 'YouTube 연결'}
          </Button>
        )}
      </div>
    </section>
  )
}
