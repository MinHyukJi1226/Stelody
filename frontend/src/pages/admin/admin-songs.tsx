import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { adminPaths, getInbox } from '@/features/admin/api'
import { adminKeys } from '@/features/admin/queries'
import { songListOptions, songEditPath } from '@/features/admin/songs'
import {
  adminLabel,
  isAdminAccessError,
  listPage,
} from '@/features/admin/format'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import {
  AdminApiAccess,
  AdminPagination,
  AdminTable,
  AdminTime,
} from './admin-shared'
import { SongNav } from './song-ui'

export function AdminSongsPage() {
  const user = useAdminUser()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const q = params.get('q') ?? ''
  const page = listPage(params.get('page'), 0, 10000)
  const size = Math.max(1, listPage(params.get('size'), 20, 50))
  const query = useQuery(songListOptions(user, q, page, size))
  const count = useQuery({
    queryKey: [...adminKeys(user), 'song-incomplete-count'],
    queryFn: ({ signal }) => getInbox(signal),
    retry: false,
    gcTime: 0,
  })
  if (isAdminAccessError(query.error) || isAdminAccessError(count.error))
    return (
      <AdminApiAccess
        error={isAdminAccessError(query.error) ? query.error : count.error}
        user={user}
      />
    )
  function update(q: string, page: number) {
    setParams({ q, page: String(page), size: String(size) })
  }
  return (
    <>
      <h1 className="admin-heading">노래 목록</h1>
      <SongNav />
      <section
        className="admin-panel"
        aria-label="노래 목록"
        aria-busy={query.isFetching}
      >
        <SongSearch
          key={q}
          q={q}
          pending={query.isFetching}
          onSearch={(value) => update(value, 0)}
        />
        <div className="flex flex-wrap items-center justify-between gap-4">
          <p>{page + 1}페이지</p>
          <Button variant="primary" asChild>
            <Link
              to={`/admin/songs/new?${new URLSearchParams({ returnTo: location.pathname + location.search })}`}
            >
              노래 수동 등록
            </Link>
          </Button>
          <Button asChild>
            <Link to={adminPaths.registrations}>자동 등록 정보 보완 목록</Link>
          </Button>
        </div>
        <p className="text-xs text-muted">
          {count.isError
            ? '자동 등록 정보 보완 건수를 조회하지 못했습니다.'
            : count.isPending
              ? '자동 등록 정보 보완 건수 확인 중…'
              : `자동 등록 정보 보완 ${count.data.incompleteAutoRegistrationCount}건 · 전체 곡의 누락 건수가 아닙니다.`}
        </p>
        <p className="text-sm text-muted">
          공개 설정과 정보 완성도는 별개입니다. 별칭은 선택 항목입니다.
        </p>
        {query.isError ? (
          <div className="admin-notice" role="status">
            <h2>조회하지 못했어요</h2>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </div>
        ) : query.isPending ? (
          <SongLoading />
        ) : (
          <AdminTable
            headers={[
              '곡명',
              '공개 설정',
              '정보 완성도',
              '참여자',
              '최초 발견 (KST)',
              '작업',
            ]}
            widths={[24, 12, 17, 22, 17, 8]}
            empty="검색 조건에 맞는 곡이 없습니다."
            rows={query.data.items.map((song) => ({
              key: song.id,
              cells: [
                <strong>{song.name}</strong>,
                adminLabel(song.status),
                <>
                  {song.informationComplete ? '완성' : '보완 필요'}
                  <p className="text-xs text-muted">
                    {song.missingFields
                      .filter((x) => x !== 'aliases')
                      .map(adminLabel)
                      .join(' · ')}
                  </p>
                </>,
                song.participants.length
                  ? song.participants.map((p) => (
                      <p key={`${p.kind}:${p.id}`}>
                        {p.name}
                        <span className="block text-xs text-muted">
                          {p.kind === 'MEMBER' ? '멤버' : '외부 아티스트'} ·{' '}
                          {p.confirmed ? '확인됨' : '미확정'}
                        </span>
                      </p>
                    ))
                  : '참여자 미등록',
                <AdminTime value={song.discoveredAt} empty="발견 기록 없음" />,
                <Button asChild>
                  <Link
                    to={songEditPath(
                      song.id,
                      location.pathname + location.search,
                    )}
                  >
                    수정
                  </Link>
                </Button>,
              ],
            }))}
          />
        )}
        <AdminPagination
          page={page}
          hasNext={query.data?.hasNext ?? false}
          pending={query.isFetching}
          onPage={(next) => update(q, next)}
        />
      </section>
    </>
  )
}
export function SongLoading() {
  return (
    <div className="grid gap-4" role="status">
      <p>불러오는 중…</p>
      {[0, 1, 2].map((x) => (
        <div
          key={x}
          aria-hidden="true"
          className="h-16 animate-pulse rounded-xl bg-bg"
        />
      ))}
    </div>
  )
}

function SongSearch({
  q,
  pending,
  onSearch,
}: {
  q: string
  pending: boolean
  onSearch: (value: string) => void
}) {
  const [search, setSearch] = useState(q)
  useEffect(() => {
    // A fast Back can interrupt the search transition before the new q is committed.
    const restore = () =>
      setSearch(new URLSearchParams(window.location.search).get('q') ?? '')
    window.addEventListener('popstate', restore)
    return () => window.removeEventListener('popstate', restore)
  }, [])
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        onSearch(search)
      }}
      className="grid gap-4"
    >
      <TextField
        label="곡 제목·검색 별칭"
        placeholder="곡 제목·검색 별칭으로 검색"
        value={search}
        maxLength={200}
        onChange={(e) => setSearch(e.target.value)}
        description="검색어의 공백·대소문자는 구분하지 않습니다."
      />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" disabled={pending}>
          검색
        </Button>
        <Button
          onClick={() => {
            setSearch('')
            onSearch('')
          }}
        >
          초기화
        </Button>
      </div>
    </form>
  )
}
