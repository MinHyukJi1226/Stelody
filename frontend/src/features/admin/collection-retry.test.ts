import { expect, it } from 'vitest'
import {
  failureReturnPath,
  receiptKey,
  retryUnavailable,
  type Run,
} from './collection-retry'
const now = '2026-10-31T12:00:00Z'
const run: Run = {
  id: '00000000-0000-4000-8000-000000000001',
  kind: 'VIDEO',
  status: 'FAILED',
  attempt: 2,
  logicalSlot: '2026-10-29T12:00:00Z',
  startedAt: '2026-10-31T11:00:00Z',
  finishedAt: null,
  mode: null,
  channelId: null,
  errorCode: null,
  totalCount: 1,
  observedCount: 0,
  skippedCount: 0,
  pendingCount: 1,
  pages: 0,
  candidates: 0,
  ruleVersion: null,
}
it('48시간 경계는 포함하며 VIDEO는 슬롯을 기준으로 판단한다', () => {
  expect(retryUnavailable(run, now)).toBeNull()
  expect(
    retryUnavailable({ ...run, logicalSlot: '2026-10-29T11:59:59Z' }, now),
  ).toBe('재시도 가능한 기간이 지났습니다')
  expect(retryUnavailable({ ...run, logicalSlot: null }, now)).toBe(
    '재시도 가능한 기간이 지났습니다',
  )
})
it('DISCOVERY는 시작 시각을 사용하며 미래 시각과 성공 작업을 거절한다', () => {
  expect(
    retryUnavailable({ ...run, kind: 'DISCOVERY', logicalSlot: null }, now),
  ).toBeNull()
  expect(
    retryUnavailable(
      { ...run, kind: 'DISCOVERY', startedAt: '2026-11-01T00:00:00Z' },
      now,
    ),
  ).toBe('재시도 가능한 기간이 지났습니다')
  expect(retryUnavailable({ ...run, status: 'SUCCEEDED' }, now)).toBe(
    '작업의 최신 상태를 확인해 주세요',
  )
})
it('실패 기간과 페이지를 복원하며 외부 주소로 복귀하지 않는다', () => {
  const p =
    '/admin/failures?from=2026-10-30T00:00:00Z&to=2026-10-31T00:00:00Z&page=2'
  expect(failureReturnPath(p)).toBe(p)
  expect(failureReturnPath('//evil.example/admin/failures')).toBe(
    '/admin/failures',
  )
  expect(receiptKey('a', 'VIDEO', run.id)).not.toBe(
    receiptKey('b', 'VIDEO', run.id),
  )
})
