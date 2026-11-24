import { useQuery } from '@tanstack/react-query'
import { Outlet, useLocation } from 'react-router'
import { StatePanel } from '@/shared/ui/state-panel'
import { PageState } from '@/shared/ui/page-state'
import { StatePageLayout } from '@/shared/ui/state-page-layout'
import { LoginRedirect } from './login-navigation'
import { sessionOptions } from './session'

export function AuthGate() {
  const session = useQuery(sessionOptions)
  const location = useLocation()
  if (session.isPending)
    return (
      <StatePageLayout>
        <StatePanel title="로그인 확인 중…" busy />
      </StatePageLayout>
    )
  if (session.isError)
    return (
      <StatePageLayout>
        <PageState
          heading="h1"
          kind="error"
          title="로그인 상태를 확인하지 못했습니다"
          onRetry={() => void session.refetch()}
          action={{ to: '/login', label: '로그인' }}
          showHome={false}
        />
      </StatePageLayout>
    )
  if (!session.data)
    return (
      <LoginRedirect
        returnTo={`${location.pathname}${location.search}${location.hash}`}
      />
    )
  return <Outlet />
}
