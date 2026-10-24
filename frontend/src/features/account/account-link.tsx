import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { Button } from '@/shared/ui/button'
import { accountPath } from './api'

export function AccountLink({ onNavigate }: { onNavigate?: () => void }) {
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
  })
  return session.data ? (
    <Button asChild>
      <NavLink to={accountPath} onClick={onNavigate}>
        계정 관리
      </NavLink>
    </Button>
  ) : null
}
