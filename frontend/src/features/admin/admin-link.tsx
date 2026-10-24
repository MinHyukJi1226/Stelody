import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { Button } from '@/shared/ui/button'
import { adminPaths, isActiveAdmin } from './api'

export function AdminLink({
  mobile,
  onNavigate,
}: {
  mobile?: boolean
  onNavigate?: () => void
}) {
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
  })
  if (!session.isSuccess || session.isFetching || !isActiveAdmin(session.data))
    return null
  return (
    <Button asChild className="admin-entry">
      <NavLink to={adminPaths.inbox} onClick={onNavigate}>
        {mobile ? '관리자 작업함' : '관리자'}
      </NavLink>
    </Button>
  )
}
