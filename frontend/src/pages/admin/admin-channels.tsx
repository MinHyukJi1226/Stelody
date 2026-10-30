import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isAdminAccessError, listPage } from '@/features/admin/format'
import {
  channelListOptions,
  channelEditPath,
  channelLabels,
} from '@/features/admin/channels'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { SongNav } from './song-ui'
import { SongLoading } from './admin-songs'

export function AdminChannelsPage() {
  const user = useAdminUser()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const q = params.get('q') ?? ''
  const page = listPage(params.get('page'), 0, 10000),
    size = Math.max(1, listPage(params.get('size'), 20, 50))
  const query = useQuery(channelListOptions(user, q, page, size))
  const returnTo = location.pathname + location.search
  const update = (q: string, page: number) =>
    setParams({ q, page: String(page), size: String(size) })
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">공식 채널 관리</h1>
      <ChannelSearch
        key={q}
        q={q}
        pending={query.isFetching}
        onSearch={(value) => update(value, 0)}
        returnTo={returnTo}
      />
      <section
        className="grid gap-4"
        aria-label="채널 목록"
        aria-busy={query.isFetching}
      >
        {query.isPending ? (
          <SongLoading />
        ) : query.isError ? (
          <div className="admin-panel" role="alert">
            <h2 className="text-lg font-bold">
              채널 목록을 조회하지 못했습니다
            </h2>
            <p className="text-sm text-muted">
              검색어와 요청한 페이지를 유지했습니다.
            </p>
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          </div>
        ) : query.data.items.length ? (
          query.data.items.map((channel) => (
            <div className="grid gap-3" key={channel.id}>
              <Link
                className="admin-panel gap-2 rounded-xl"
                to={channelEditPath(channel.id, returnTo)}
              >
                <h2 className="text-lg font-bold">{channel.name}</h2>
                <p className="text-sm text-muted">
                  {channelLabels[channel.status]}
                </p>
              </Link>
              <Button asChild className="justify-self-start">
                <Link to={channelEditPath(channel.id, returnTo)}>
                  채널 수정
                </Link>
              </Button>
            </div>
          ))
        ) : (
          <div className="admin-panel" role="status">
            <h2 className="text-lg font-bold">목록이 비어 있습니다</h2>
            <p className="text-sm text-muted">
              검색 조건에 맞는 채널이 없습니다. 새 채널을 등록할 수 있습니다.
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
function ChannelSearch({
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
        placeholder="채널명·YouTube 채널 ID"
        value={value}
        maxLength={200}
        onChange={(e) => setValue(e.target.value)}
      />
      <Button type="submit" variant="primary" disabled={pending}>
        검색
      </Button>
      <Button asChild variant="primary">
        <Link to={`/admin/channels/new?${new URLSearchParams({ returnTo })}`}>
          채널 등록
        </Link>
      </Button>
    </form>
  )
}
