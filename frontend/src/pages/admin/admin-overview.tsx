import { adminLabel } from '@/features/admin/format'
import { useAdminUser } from '@/features/admin/admin-context'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { adminPaths, failurePath, type Health } from '@/features/admin/api'
import {
  adminCollectionOptions,
  adminInboxOptions,
} from '@/features/admin/queries'
import { Button } from '@/shared/ui/button'
import { AdminScreen, AdminTime } from './admin-shared'

export function AdminInboxPage() {
  const query = useQuery(adminInboxOptions(useAdminUser()))
  const data = query.data
  const cards = data && [
    [
      '신규 검토',
      `${data.inbox.newReviewCount.toLocaleString('ko-KR')}건`,
      adminPaths.reviews,
    ],
    [
      '정보 보완',
      `${data.inbox.incompleteAutoRegistrationCount.toLocaleString('ko-KR')}건`,
      adminPaths.registrations,
    ],
    [
      '특별 목적 후보',
      `${data.inbox.specialEventReviewCount.toLocaleString('ko-KR')}건`,
      adminPaths.special,
    ],
    [
      '수집 실패 작업',
      `${data.inbox.collectionFailureCount.toLocaleString('ko-KR')}개 작업`,
      failurePath({
        from: data.inbox.failureWindowStart,
        to: data.inbox.checkedAt,
      }),
    ],
  ]
  return (
    <AdminScreen title="관리자 작업함" query={query} back={false}>
      {data && (
        <>
          <p className="text-sm text-muted">
            집계 시각 <AdminTime value={data.inbox.checkedAt} />
          </p>
          <div className="grid gap-3 md:grid-cols-4">
            {cards?.map(([label, count, to]) => (
              <div
                className="flex flex-col items-start gap-2 rounded-2xl bg-bg p-3"
                key={to}
              >
                <h2 className="text-sm">{label}</h2>
                <p
                  className={`text-2xl font-bold ${to?.startsWith(adminPaths.failures) ? 'text-warning' : ''}`}
                >
                  {count}
                </p>
                <Button asChild>
                  <Link to={to!}>목록 보기</Link>
                </Button>
              </div>
            ))}
          </div>
          <div className="grid gap-4 text-xs text-muted">
            <p>
              실패 집계 기간 ·{' '}
              <AdminTime value={data.inbox.failureWindowStart} />–
              <AdminTime value={data.inbox.checkedAt} />
            </p>
            <p>
              정보 보완: 원곡·원곡 아티스트·검색 확인·TJ·KY 확인 누락
              기준입니다. 별칭은 선택 항목입니다.
            </p>
            <p>
              수집 실패는 최근 24시간의 미해결 작업 수입니다. 실패 영상 수가
              아닙니다.
            </p>
            <p>
              특별 목적 후보에는 근거 만료·설정 변경으로 재확인이 필요한 대상도
              포함됩니다.
            </p>
          </div>
          <h2 className="text-lg font-bold">마지막 수집 성공</h2>
          <div className="grid gap-4 md:grid-cols-2">
            <HealthCard health={data.collection.discovery} discovery compact />
            <HealthCard health={data.collection.video} compact />
          </div>
          <Button asChild>
            <Link to={adminPaths.collection}>수집 현황 보기</Link>
          </Button>
          <div className="admin-notice">
            <p>후속 기능 · 준비 중</p>
            <p className="text-muted">
              신규 등록, 원곡·영상 편집, 검토 처리, 실패 작업 재시도, 자동 공개
              변경은 후속 범위입니다.
            </p>
          </div>
        </>
      )}
    </AdminScreen>
  )
}

export function AdminCollectionPage() {
  const query = useQuery(adminCollectionOptions(useAdminUser()))
  const data = query.data
  return (
    <AdminScreen title="수집 현황" query={query}>
      {data && (
        <>
          <p className="text-sm text-muted">
            조회 기준 <AdminTime value={data.checkedAt} />
          </p>
          <div className="grid gap-4 md:grid-cols-2">
            <HealthCard health={data.discovery} discovery />
            <HealthCard health={data.video} />
          </div>
          <p className="text-xs text-muted">
            서버 중지나 작업 지연에 따라 실제 실행은 늦어질 수 있습니다.
          </p>
          <p className="text-sm text-muted">
            대기 후보 {data.pendingReviews.toLocaleString('ko-KR')}건 · 보류
            후보 {data.deferredReviews.toLocaleString('ko-KR')}건
          </p>
          <div className="admin-notice">
            <p>실패 작업 재시도 · 후속 범위</p>
            <p className="text-muted">
              현재 화면에서는 실행 결과만 조회합니다. 재시도 버튼과 자동 공개
              변경은 제공하지 않습니다.
            </p>
          </div>
        </>
      )}
    </AdminScreen>
  )
}

function HealthCard({
  health,
  discovery,
  compact,
}: {
  health: Health
  discovery?: boolean
  compact?: boolean
}) {
  return (
    <section className="grid content-start gap-3 rounded-2xl bg-bg p-4">
      <h3 className="text-lg font-bold">
        {discovery ? '신규 영상 탐색' : '조회수·영상 상태 갱신'}
      </h3>
      <p className="text-sm text-brand">
        {discovery ? '매일 00:00 KST' : '매시간 정각 KST'}
      </p>
      <dl className="grid gap-3 text-sm">
        <div>
          <dt className="mb-1 text-xs text-muted">마지막 성공</dt>
          <dd>
            <AdminTime value={health.lastSuccessAt} empty="성공 기록 없음" />
          </dd>
        </div>
        {!compact && (
          <>
            <div>
              <dt className="mb-1 text-xs text-muted">최근 작업</dt>
              <dd>
                {health.latest
                  ? adminLabel(health.latest.status)
                  : '실행 기록 없음'}
              </dd>
            </div>
            <div>
              <dt className="mb-1 text-xs text-muted">마지막 실패</dt>
              <dd>
                <AdminTime
                  value={health.lastFailureAt}
                  empty="실패 기록 없음"
                />
              </dd>
            </div>
            <div>
              <dt className="mb-1 text-xs text-muted">누락된 예약 구간</dt>
              <dd>{health.missedSlots.toLocaleString('ko-KR')}개</dd>
            </div>
          </>
        )}
      </dl>
      {!compact && health.delayed && (
        <p className="text-sm text-warning">수집 지연</p>
      )}
      {!compact && health.runningOverdue && (
        <p className="text-sm text-warning">장시간 실행 중</p>
      )}
    </section>
  )
}
