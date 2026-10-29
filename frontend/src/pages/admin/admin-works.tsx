import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isAdminAccessError, listPage } from '@/features/admin/format'
import { workListOptions, workEditPath } from '@/features/admin/works'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { SongNav } from './song-ui'
import { SongLoading } from './admin-songs'

export function AdminWorksPage() {
  const user = useAdminUser()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const q = params.get('q') ?? ''
  const page = listPage(params.get('page'), 0, 10000)
  const size = Math.max(1, listPage(params.get('size'), 20, 50))
  const query = useQuery(workListOptions(user, q, page, size))
  const returnTo = location.pathname + location.search
  const update = (value: string, next: number) =>
    setParams({ q: value, page: String(next), size: String(size) })
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">원곡 관리</h1>
      <WorkSearch
        key={q}
        q={q}
        pending={query.isFetching}
        search={(value) => update(value, 0)}
        returnTo={returnTo}
      />
      <section
        className="grid gap-4"
        aria-label="원곡 목록"
        aria-busy={query.isFetching}
      >
        {query.isError ? (
          <div role="alert" className="admin-panel">
            <h2 className="text-lg font-bold">
              원곡 목록을 조회하지 못했습니다
            </h2>
            <p className="text-sm text-muted">
              검색어와 요청한 페이지를 유지했습니다.
            </p>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </div>
        ) : query.isPending ? (
          <SongLoading />
        ) : query.data.items.length ? (
          query.data.items.map((work) => (
            <article className="work-list-row" key={work.id}>
              <div className="grid min-w-0 gap-2">
                <h2 className="font-bold">{work.name}</h2>
                <p className="text-sm text-muted">공유 원곡 정보</p>
              </div>
              <Button asChild>
                <Link to={workEditPath(work.id, returnTo)}>원곡 수정</Link>
              </Button>
            </article>
          ))
        ) : (
          <div className="admin-panel" role="status">
            <h2 className="text-lg font-bold">목록이 비어 있습니다</h2>
            <p className="text-sm text-muted">
              검색 조건에 맞는 원곡이 없습니다. 새 원곡을 등록할 수 있습니다.
            </p>
          </div>
        )}
      </section>
      <AdminPagination
        page={page}
        hasNext={query.data?.hasNext ?? false}
        pending={query.isFetching}
        onPage={(next) => update(q, next)}
      />
    </>
  )
}
function WorkSearch({
  q,
  pending,
  search,
  returnTo,
}: {
  q: string
  pending: boolean
  search: (q: string) => void
  returnTo: string
}) {
  const [value, setValue] = useState(q)
  return (
    <form
      className="work-list-search"
      onSubmit={(e) => {
        e.preventDefault()
        search(value)
      }}
    >
      <TextField
        label="검색"
        placeholder="이름·제목 검색"
        value={value}
        maxLength={200}
        onChange={(e) => setValue(e.target.value)}
      />
      <Button variant="primary" type="submit" disabled={pending}>
        검색
      </Button>
      <Button variant="primary" asChild>
        <Link to={`/admin/works/new?${new URLSearchParams({ returnTo })}`}>
          원곡 등록
        </Link>
      </Button>
    </form>
  )
}
