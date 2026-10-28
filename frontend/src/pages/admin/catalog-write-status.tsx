import type { ReactNode } from 'react'
import { Button } from '@/shared/ui/button'
import { ReadValue } from './song-ui'

export function CatalogWriteStatus({
  mode,
  busy,
  notice,
  base,
  draft,
  latest,
  check,
  review,
  back,
}: {
  mode: 'conflict' | 'unknown' | 'checked'
  busy: boolean
  notice: string
  base: ReactNode
  draft: ReactNode
  latest: ReactNode | null
  check: () => void
  review: () => void
  back: () => void
}) {
  return (
    <div className="grid gap-6">
      <h2 id="original-artist-title" className="text-lg font-bold">
        {mode === 'conflict'
          ? '다른 관리자의 변경이 있어요'
          : mode === 'checked'
            ? '현재 저장값을 확인했어요'
            : '저장 결과를 확인해야 해요'}
      </h2>
      <p className="text-sm text-muted">
        {mode === 'conflict'
          ? '내 입력을 보관했습니다. 최신 정보와 비교한 뒤 다시 확인해 주세요.'
          : mode === 'checked'
            ? '현재 값이 내 입력과 같아도 이 요청의 성공을 증명하는 것은 아닙니다.'
            : '응답을 받지 못했습니다. 이미 반영됐을 수 있어 성공·실패로 확정할 수 없습니다.'}
      </p>
      {notice && (
        <p role="status" className="text-sm text-warning">
          {notice}
        </p>
      )}
      {mode === 'conflict' && (
        <>
          <ReadValue label="최초 조회값">{base}</ReadValue>
          <ReadValue label="내 입력">{draft}</ReadValue>
        </>
      )}
      {latest !== null && (
        <ReadValue label={mode === 'checked' ? '현재 저장값' : '최신 저장값'}>
          {latest}
        </ReadValue>
      )}
      {mode === 'conflict' && (
        <p className="text-xs text-muted">
          최신 정보를 다시 읽습니다. 자동 덮어쓰기는 하지 않습니다.
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {latest !== null ? (
          <Button variant="primary" disabled={busy} onClick={review}>
            {mode === 'conflict' ? '내 선택을 다시 검토' : '편집으로 돌아가기'}
          </Button>
        ) : (
          <Button variant="primary" disabled={busy} onClick={check}>
            {busy ? '조회 중…' : '최신 저장값 조회'}
          </Button>
        )}
        <Button disabled={busy} onClick={back}>
          돌아가기
        </Button>
      </div>
    </div>
  )
}
