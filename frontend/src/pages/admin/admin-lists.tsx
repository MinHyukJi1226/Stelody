import { Button } from '@/shared/ui/button'
import { songEditPath } from '@/features/admin/songs'
import { adminLabel } from '@/features/admin/format'
import { useAdminUser } from '@/features/admin/admin-context'
import { useQuery } from '@tanstack/react-query'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router'
import { adminPaths, type FailureWindow } from '@/features/admin/api'
import {
  adminFailureOptions,
  adminRegistrationOptions,
  adminSpecialOptions,
  adminWindowOptions,
} from '@/features/admin/queries'
import {
  AdminPagination,
  AdminScreen,
  AdminTable,
  AdminTime,
  ReadOnlyNote,
} from './admin-shared'

function useAdminPage() {
  const [params, setParams] = useSearchParams()
  const raw = params.get('page') ?? '0'
  const page =
    /^\d+$/.test(raw) && Number.isSafeInteger(Number(raw)) ? Number(raw) : 0
  const onPage = (next: number) => {
    const updated = new URLSearchParams(params)
    updated.set('page', String(next))
    setParams(updated)
  }
  return { page, onPage, params }
}

export function AdminRegistrationsPage() {
  const location = useLocation()
  const { page, onPage } = useAdminPage()
  const query = useQuery(adminRegistrationOptions(useAdminUser(), page))
  return (
    <AdminScreen title="정보 보완" query={query}>
      <p className="text-sm text-muted">정보가 미완성인 자동 등록 곡</p>
      <p className="text-sm text-muted">
        원곡·원곡 아티스트·검색 확인·TJ·KY 확인 중 누락이 있는 자동 등록 곡을
        표시합니다. 별칭은 선택 항목이며 보완 필요 여부를 결정하지 않습니다.
        공개 상태와 정보 완성도는 별개입니다.
      </p>
      <AdminTable
        widths={[24, 14, 22, 28, 12]}
        headers={[
          '곡명',
          '공개 상태',
          '자동 등록 시각 (KST)',
          '누락 항목',
          '작업',
        ]}
        rows={(query.data?.items ?? []).map((item) => ({
          key: item.reviewId,
          cells: [
            item.title,
            adminLabel(item.visibility),
            <AdminTime value={item.processedAt} />,
            item.missingFields?.map(adminLabel).join(' · ') || '누락 항목 없음',
            <Button asChild>
              <Link
                to={songEditPath(
                  item.songId,
                  location.pathname + location.search,
                )}
              >
                수정
              </Link>
            </Button>,
          ],
        }))}
        empty="정보 보완이 필요한 자동 등록 곡이 없습니다."
      />
      {query.data && (
        <AdminPagination
          page={page}
          hasNext={query.data.hasNext ?? false}
          pending={query.isFetching}
          onPage={onPage}
        />
      )}
      <p className="text-xs text-muted">
        수정 화면에서 곡 정보를 보완할 수 있습니다. 저장 후 이 목록을 다시
        조회합니다.
      </p>
    </AdminScreen>
  )
}
export function AdminSpecialPage() {
  const { page, onPage } = useAdminPage()
  const query = useQuery(adminSpecialOptions(useAdminUser(), page))
  return (
    <AdminScreen title="특별 목적 후보" query={query}>
      <p className="text-sm text-muted">검토 대기 · 확정 전 후보</p>
      <p className="text-sm text-muted">
        날짜 일치는 후보 근거이며 특별 목적 확정이나 자동 공개를 의미하지
        않습니다. 근거가 현재 유효하지 않은 후보도 재확인 대상으로 포함합니다.
      </p>
      <AdminTable
        headers={[
          '곡명 · 현재 설정',
          '근거 상태',
          '후보 근거',
          '생성 시각 (KST)',
        ]}
        widths={[23, 27, 30, 20]}
        rows={(query.data?.items ?? []).map((item) => ({
          key: item.id,
          cells: [
            <>
              {item.title}
              <p>
                {item.isSpecialEvent
                  ? `현재 문구: ${item.specialEventLabel ?? '등록된 문구 없음'}`
                  : '특별 목적 미설정'}
              </p>
            </>,
            <span className={item.basisCurrent ? '' : 'text-warning'}>
              {item.basisCurrent ? '근거 유효' : '재확인 필요'}
            </span>,
            <>
              {!item.basisCurrent && <p>근거 만료 또는 설정 변경</p>}
              {item.evidence.length ? (
                item.evidence.map((e, i) => (
                  <p key={i}>
                    {e.memberName} · {adminLabel(e.reason ?? '')}
                    <br />
                    공개일 {e.publishedDate}
                  </p>
                ))
              ) : (
                <p>유효한 근거 없음</p>
              )}
            </>,
            <AdminTime value={item.createdAt} />,
          ],
        }))}
        empty="특별 목적 검토 대기 후보가 없습니다."
      />
      {query.data && (
        <AdminPagination
          page={page}
          hasNext={query.data.hasMore}
          pending={query.isFetching}
          onPage={onPage}
        />
      )}
      <ReadOnlyNote />
    </AdminScreen>
  )
}

export function AdminFailuresPage() {
  const { params } = useAdminPage()
  const from = params.get('from')
  const to = params.get('to')
  if (!from || !to) return <FailureWindowPage />
  return <FailuresWithWindow window={{ from, to }} />
}
function FailureWindowPage() {
  const { params } = useAdminPage()
  const query = useQuery(adminWindowOptions(useAdminUser()))
  if (query.isSuccess) {
    const target = new URLSearchParams(params)
    target.set('from', query.data.failureWindowStart)
    target.set('to', query.data.checkedAt)
    return <Navigate replace to={`${adminPaths.failures}?${target}`} />
  }
  return (
    <AdminScreen title="수집 실패 작업" query={query}>
      {null}
    </AdminScreen>
  )
}
function FailuresWithWindow({ window }: { window: FailureWindow }) {
  const { page, onPage } = useAdminPage()
  const query = useQuery(adminFailureOptions(useAdminUser(), page, window))
  return (
    <AdminScreen title="수집 실패 작업" query={query}>
      <p className="text-sm text-muted">최근 24시간 · 미해결 작업</p>
      <p className="text-sm">
        <AdminTime value={window.from} />–<AdminTime value={window.to} />
      </p>
      <p className="text-sm text-muted">
        영상 수가 아닌 실패 작업 수입니다. 실패 종료 시각(없으면 시작 시각)이
        해당 기간에 속하고, 아직 해결되지 않은 작업을 표시합니다. 별도 실패
        실행은 각각 표시됩니다.
      </p>
      <AdminTable
        headers={[
          '작업 종류',
          '상태',
          '시작 시각 (KST)',
          '종료 시각 (KST)',
          '시도 횟수',
          '오류 코드',
        ]}
        widths={[18, 14, 18, 18, 10, 22]}
        rows={(query.data?.items ?? []).map((item) => ({
          key: `${item.kind}:${item.id}`,
          cells: [
            adminLabel(item.kind),
            adminLabel(item.status),
            <AdminTime value={item.startedAt} />,
            <AdminTime value={item.finishedAt} empty="종료 시각 없음" />,
            `${item.attempt}회`,
            item.errorCode ?? '오류 코드 없음',
          ],
        }))}
        empty="해당 기간에 미해결 실패 작업이 없습니다."
      />
      {query.data && (
        <AdminPagination
          page={page}
          hasNext={query.data.hasNext}
          pending={query.isFetching}
          onPage={onPage}
        />
      )}
      <ReadOnlyNote />
    </AdminScreen>
  )
}
