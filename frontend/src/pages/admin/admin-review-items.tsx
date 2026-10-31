import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import {
  reviewListOptions,
  reviewStatusLabels,
  reviewDetailPath,
} from '@/features/admin/review-items'
import { isAdminAccessError, listPage } from '@/features/admin/format'
import { Button } from '@/shared/ui/button'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { SongNav, Choices } from './song-ui'
import { SongLoading } from './admin-songs'
export function AdminReviewItemsPage() {
  const user = useAdminUser(),
    [params, setParams] = useSearchParams(),
    location = useLocation()
  const raw = params.get('status') ?? 'ALL',
    status = ['ALL', 'PENDING', 'IGNORED', 'REGISTERED'].includes(raw)
      ? raw
      : 'ALL',
    filter = params.get('disposition') ?? '',
    disposition = ['REVIEW', 'EXCLUDED', 'DEFERRED'].includes(filter)
      ? filter
      : '',
    page = listPage(params.get('page'), 0, 10000),
    size = Math.max(1, listPage(params.get('size'), 20, 50))
  const query = useQuery(
    reviewListOptions(user, status, disposition, page, size),
  )
  function update(values: Record<string, string>) {
    setParams({
      status,
      disposition,
      page: String(page),
      size: String(size),
      ...values,
    })
  }
  const returnTo = location.pathname + location.search
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">개별 후보 검토</h1>
      <div className="grid gap-3">
        <Choices
          label="검토 상태"
          value={status}
          values={[['ALL', '전체'], ...Object.entries(reviewStatusLabels)]}
          onChange={(v) => update({ status: v, page: '0' })}
        />
        <Choices
          label="분류 결과"
          value={disposition}
          values={[
            ['', '전체'],
            ['REVIEW', '검토 대상'],
            ['EXCLUDED', '분류 제외'],
            ['DEFERRED', '수집 분류 유예'],
          ]}
          onChange={(v) => update({ disposition: v, page: '0' })}
        />
      </div>
      <p className="text-sm text-muted">
        검토 상태와 수집 분류 결과는 별개입니다.
      </p>
      <section
        className="grid gap-4"
        aria-label="개별 후보 목록"
        aria-busy={query.isFetching}
      >
        {query.isPending ? (
          <SongLoading />
        ) : query.isError ? (
          <div className="admin-panel" role="alert">
            <h2 className="text-lg font-bold">
              후보 목록을 조회하지 못했습니다
            </h2>
            <p className="text-sm text-muted">
              요청한 필터와 페이지를 유지했습니다.
            </p>
            <Button onClick={() => void query.refetch()}>다시 조회</Button>
          </div>
        ) : query.data.items.length ? (
          query.data.items.map((item) => (
            <div key={item.id} className="grid gap-3">
              <Link
                className="admin-panel gap-2"
                to={reviewDetailPath(item.id, returnTo)}
              >
                <h2 className="text-lg font-bold">
                  {item.title ?? '제목 없음'}
                </h2>
                <p className="text-sm text-muted">
                  {reviewStatusLabels[item.status] ?? item.status} ·{' '}
                  {item.disposition} / {item.suggestedType}
                </p>
              </Link>
              <Button asChild className="justify-self-start">
                <Link to={reviewDetailPath(item.id, returnTo)}>후보 상세</Link>
              </Button>
            </div>
          ))
        ) : (
          <div className="admin-panel" role="status">
            <h2 className="text-lg font-bold">목록이 비어 있습니다</h2>
            <p>조건에 맞는 후보가 없습니다.</p>
          </div>
        )}
      </section>
      <AdminPagination
        page={page}
        hasNext={query.data?.hasNext ?? false}
        pending={query.isFetching}
        onPage={(next) => update({ page: String(next) })}
      />
    </>
  )
}
