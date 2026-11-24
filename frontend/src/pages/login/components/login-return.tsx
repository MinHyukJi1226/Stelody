import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import { preparePendingAction } from '@/features/auth/pending-action'
import { LoginSkeleton } from './login-card'
import { clearLoginReturn, safeReturnPath } from '@/features/auth/return-path'

export function LoginReturn({
  userId,
  returnTo,
}: {
  userId: string
  returnTo: string
}) {
  const navigate = useNavigate()
  useEffect(() => {
    const target = safeReturnPath(returnTo)
    preparePendingAction(userId, target)
    clearLoginReturn()
    void navigate(target, { replace: true })
  }, [navigate, userId, returnTo])
  return <LoginSkeleton />
}
