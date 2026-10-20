import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import { preparePendingAction } from '@/features/auth/pending-action'
import { LoginSkeleton } from './login-card'

export function LoginReturn({
  userId,
  returnTo,
}: {
  userId: string
  returnTo: string
}) {
  const navigate = useNavigate()
  useEffect(() => {
    preparePendingAction(userId, returnTo)
    void navigate(returnTo, { replace: true })
  }, [navigate, userId, returnTo])
  return <LoginSkeleton />
}
