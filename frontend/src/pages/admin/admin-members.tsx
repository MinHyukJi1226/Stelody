import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isAdminAccessError, listPage } from '@/features/admin/format'
import { memberListOptions, memberEditPath } from '@/features/admin/members'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { SongNav } from './song-ui'
import { SongLoading } from './admin-songs'

export function AdminMembersPage() {
  const user = useAdminUser()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const q = params.get('q') ?? ''
  const page = listPage(params.get('page'), 0, 10000),
    size = Math.max(1, listPage(params.get('size'), 20, 50))
  const query = useQuery(memberListOptions(user, q, page, size))
  const returnTo = location.pathname + location.search
  const update = (q: string, page: number) =>
    setParams({ q, page: String(page), size: String(size) })
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">멤버 관리</h1>
      <MemberSearch
        key={q}
        q={q}
        pending={query.isFetching}
        onSearch={(value) => update(value, 0)}
        returnTo={returnTo}
      />
      <section
        className="grid gap-4"
        aria-label="멤버 목록"
        aria-busy={query.isFetching}
      >
        {query.isPending ? (
          <SongLoading />
        ) : query.isError ? (
          <div className="admin-panel" role="alert">
            <h2 className="text-lg font-bold">
              멤버 목록을 조회하지 못했습니다
            </h2>
            <p className="text-sm text-muted">
              검색어와 요청한 페이지를 유지했습니다.
            </p>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </div>
        ) : query.data.items.length ? (
          query.data.items.map((member) => (
            <div className="grid gap-3" key={member.id}>
              <Link
                className="admin-panel gap-2 rounded-xl"
                to={memberEditPath(member.id, returnTo)}
              >
                <h2 className="text-lg font-bold">{member.name}</h2>
                <p className="text-sm text-muted">
                  {member.status === 'ACTIVE' ? '활동 중' : '졸업'}
                </p>
              </Link>
              <Button asChild className="justify-self-start">
                <Link to={memberEditPath(member.id, returnTo)}>멤버 수정</Link>
              </Button>
            </div>
          ))
        ) : (
          <div className="admin-panel" role="status">
            <h2 className="text-lg font-bold">목록이 비어 있습니다</h2>
            <p className="text-sm text-muted">
              검색 조건에 맞는 멤버가 없습니다. 새 멤버를 등록할 수 있습니다.
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
function MemberSearch({
  q,
  pending,
  onSearch,
  returnTo,
}: {
  q: string
  pending: boolean
  onSearch: (v: string) => void
  returnTo: string
}) {
  const [value, setValue] = useState(q)
  return (
    <form
      className="work-list-search"
      onSubmit={(e) => {
        e.preventDefault()
        onSearch(value)
      }}
    >
      <TextField
        label="검색"
        placeholder="이름·검색 별칭"
        value={value}
        maxLength={200}
        onChange={(e) => setValue(e.target.value)}
      />
      <Button type="submit" variant="primary" disabled={pending}>
        검색
      </Button>
      <Button asChild variant="primary">
        <Link to={`/admin/members/new?${new URLSearchParams({ returnTo })}`}>
          멤버 등록
        </Link>
      </Button>
    </form>
  )
}
