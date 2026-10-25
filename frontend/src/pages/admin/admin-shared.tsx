import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Outlet, useLocation, useMatches } from 'react-router'
import { sessionOptions, endLocalSession } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { adminPaths, isActiveAdmin } from '@/features/admin/api'
import { ApiError } from '@/shared/api/errors'
import { formatAdminTime, isAdminAccessError } from '@/features/admin/format'
import { useAdminUser } from '@/features/admin/admin-context'
import { Button } from '@/shared/ui/button'
import { SiteFooter } from '@/shared/ui/site-footer'

export function AdminGate() {
  const session = useQuery(sessionOptions)
  const preserveDraft = useMatches().some(
    (match) =>
      (match.handle as { preserveAdminDraft?: boolean } | undefined)
        ?.preserveAdminDraft,
  )
  const [admitted, setAdmitted] = useState<string | null>(null)
  if (
    session.isSuccess &&
    !session.isFetching &&
    isActiveAdmin(session.data) &&
    admitted !== session.data!.id
  ) {
    setAdmitted(session.data!.id)
  }
  let content: ReactNode
  if (
    session.isPending ||
    (session.isFetching &&
      (!isActiveAdmin(session.data) || admitted !== session.data?.id))
  )
    content = <AdminAccess state="checking" />
  else if (
    session.isError &&
    preserveDraft &&
    admitted === session.data?.id &&
    isActiveAdmin(session.data) &&
    !isAdminAccessError(session.error)
  )
    // The editor renders AD00 itself so transient session failures do not unmount its draft.
    content = <Outlet key={session.data!.id} context={session.data!.id} />
  else if (session.isError)
    content = (
      <AdminAccess state="error" onRetry={() => void session.refetch()} />
    )
  else if (!session.data) content = <AdminAccess state="login" />
  else if (!isActiveAdmin(session.data))
    content = <AdminAccess state="forbidden" />
  else content = <Outlet key={session.data.id} context={session.data.id} />
  return (
    <div className="admin-page">
      {content}
      <SiteFooter />
    </div>
  )
}

export function AdminAccess({
  state,
  onRetry,
}: {
  state: 'checking' | 'login' | 'forbidden' | 'error'
  onRetry?: () => void
}) {
  const location = useLocation()
  const copy = {
    checking: [
      '관리자 권한을 확인하고 있어요',
      '권한 확인이 끝날 때까지 잠시 기다려 주세요.',
    ],
    login: [
      '로그인이 필요해요',
      '관리자 계정으로 로그인한 뒤 다시 접근해 주세요.',
    ],
    forbidden: [
      '관리자 권한이 없어요',
      '현재 로그인한 계정으로는 관리자 화면에 접근할 수 없습니다.',
    ],
    error: [
      '권한을 확인하지 못했어요',
      '조회에 실패했습니다. 현재 건수나 정상 여부를 확인할 수 없습니다.',
    ],
  }[state]
  return (
    <>
      <h1 className="admin-heading">관리자 진입</h1>
      <section
        className="admin-panel"
        role="status"
        aria-busy={state === 'checking'}
      >
        <p className="text-sm text-brand">관리자 전용</p>
        <h2 className="text-lg font-bold">{copy[0]}</h2>
        <p className="text-sm text-muted">{copy[1]}</p>
        {state === 'login' && (
          <Button asChild variant="primary">
            <Link
              to={loginPath(
                `${location.pathname}${location.search}${location.hash}`,
              )}
            >
              로그인
            </Link>
          </Button>
        )}
        {state === 'forbidden' && (
          <Button asChild>
            <Link to="/account">계정 관리</Link>
          </Button>
        )}
        {state === 'error' && (
          <Button variant="primary" onClick={onRetry}>
            다시 조회
          </Button>
        )}
        {(state === 'login' || state === 'forbidden') && (
          <Button asChild>
            <Link to="/">홈으로</Link>
          </Button>
        )}
      </section>
    </>
  )
}

type ReadState = {
  isPending: boolean
  isFetching: boolean
  isError: boolean
  error: unknown
  refetch: () => unknown
}
export function AdminApiAccess({
  error,
  user,
}: {
  error: unknown
  user: string
}) {
  const client = useQueryClient()
  const status = error instanceof ApiError ? error.status : undefined
  useEffect(() => {
    if (
      status === 401 &&
      client.getQueryData(sessionOptions.queryKey)?.id === user
    )
      void endLocalSession(client)
  }, [client, status, user])
  return <AdminAccess state={status === 401 ? 'login' : 'forbidden'} />
}

export function AdminScreen({
  title,
  query,
  children,
  back = true,
  readOnly = true,
}: {
  title: string
  query: ReadState
  children: ReactNode
  back?: boolean
  readOnly?: boolean
}) {
  const user = useAdminUser()
  if (query.isError && isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      <h1 className="admin-heading">{title}</h1>
      <nav className="flex flex-wrap gap-2" aria-label="관리자 메뉴">
        <Button asChild>
          <Link to={adminPaths.inbox}>작업함</Link>
        </Button>
        <Button asChild>
          <Link to={adminPaths.songs}>노래 목록</Link>
        </Button>
        <Button asChild>
          <Link to={adminPaths.collection}>수집 현황</Link>
        </Button>
        <Button
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          새로고침
        </Button>
      </nav>
      <section
        className="admin-panel"
        aria-label={title}
        aria-busy={query.isFetching}
      >
        <p className="text-sm text-brand">
          {readOnly ? '관리자 · 조회 전용' : '관리자 · 검토 관리'}
        </p>
        {query.isError ? (
          <div role="status" className="admin-notice">
            <h2 className="font-bold">조회하지 못했어요</h2>
            <p>
              조회에 실패했습니다. 현재 건수나 정상 여부를 확인할 수 없습니다.
            </p>
            <Button
              variant="primary"
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              다시 조회
            </Button>
          </div>
        ) : query.isPending ? (
          <div role="status" className="grid gap-4">
            <p className="text-sm text-muted">불러오는 중…</p>
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-16 animate-pulse rounded-xl bg-bg"
                aria-hidden="true"
              />
            ))}
          </div>
        ) : (
          children
        )}
      </section>
      {back && (
        <div className="flex justify-center">
          <Button asChild>
            <Link to={adminPaths.inbox}>작업함으로 돌아가기</Link>
          </Button>
        </div>
      )}
    </>
  )
}

export function AdminTable({
  headers,
  rows,
  empty,
  widths,
}: {
  headers: string[]
  rows: { key: string; cells: ReactNode[] }[]
  empty: string
  widths?: readonly number[]
}) {
  return (
    <>
      <table className="admin-table">
        {widths && (
          <colgroup>
            {widths.map((width, i) => (
              <col key={i} style={{ width: `${width}%` }} />
            ))}
          </colgroup>
        )}
        <thead>
          <tr>
            {headers.map((h) => (
              <th key={h} scope="col">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              {row.cells.map((cell, i) => (
                <td key={headers[i]}>
                  <span className="admin-field-label">{headers[i]}</span>
                  <div>{cell}</div>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && (
        <div className="admin-notice" role="status">
          <h2>목록이 비어 있어요</h2>
          <p className="text-muted">{empty}</p>
        </div>
      )}
    </>
  )
}
export function AdminPagination({
  page,
  hasNext,
  pending,
  onPage,
}: {
  page: number
  hasNext: boolean
  pending: boolean
  onPage: (page: number) => void
}) {
  return (
    <nav className="flex flex-wrap items-center gap-2" aria-label="목록 페이지">
      <Button disabled={page === 0 || pending} onClick={() => onPage(page - 1)}>
        이전
      </Button>
      <span aria-current="page" className="text-sm text-muted">
        {page + 1}페이지
      </span>
      <Button disabled={!hasNext || pending} onClick={() => onPage(page + 1)}>
        다음
      </Button>
    </nav>
  )
}
export function ReadOnlyNote() {
  return (
    <p className="text-xs text-muted">
      목록 조회만 제공하며 등록·수정·검토 처리는 후속 범위입니다.
    </p>
  )
}
export function AdminTime({
  value,
  empty = '기록 없음',
}: {
  value: string | null | undefined
  empty?: string
}) {
  return value ? (
    <time dateTime={value}>{formatAdminTime(value)}</time>
  ) : (
    <>{empty}</>
  )
}
