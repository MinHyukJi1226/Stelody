import { useQuery } from '@tanstack/react-query'
import { Navigate, Outlet, useLocation } from 'react-router'
import { StatePanel } from '@/shared/ui/state-panel'
import { loginPath } from './return-path'
import { sessionOptions } from './session'

export function AuthGate() {
  const session = useQuery(sessionOptions)
  const location = useLocation()
  if (session.isPending) return <StatePanel title="로그인 확인 중…" />
  if (session.isError)
    return (
      <StatePanel
        title="로그인 상태를 확인하지 못했습니다"
        onRetry={() => void session.refetch()}
      />
    )
  if (!session.data)
    return (
      <Navigate
        replace
        to={loginPath(`${location.pathname}${location.search}${location.hash}`)}
      />
    )
  return <Outlet />
}
