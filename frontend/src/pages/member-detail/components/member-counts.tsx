import type { MemberCard } from '@/shared/api/types'

export function MemberCounts({ counts }: { counts: MemberCard['songCounts'] }) {
  return (
    <>
      <dl className="grid grid-cols-3 gap-2" aria-label="공개 곡 수">
        {(
          [
            ['전체', counts.total],
            ['오리지널', counts.originals],
            ['커버', counts.covers],
          ] as const
        ).map(([label, count]) => (
          <div
            key={label}
            className="flex min-w-0 flex-col gap-2 rounded-2xl bg-surface p-4"
          >
            <dt className="order-2 break-words text-sm leading-[1.5] text-muted">
              {label}
            </dt>
            <dd className="break-words text-[32px] font-bold leading-[1.5] tabular-nums">
              {count.toLocaleString('ko-KR')}
            </dd>
          </div>
        ))}
      </dl>
      <p className="text-sm leading-[1.5] text-muted">
        공동 참여 {counts.collaborations.toLocaleString('ko-KR')}곡 · 전체{' '}
        {counts.total.toLocaleString('ko-KR')}곡에 포함됩니다.
      </p>
    </>
  )
}
