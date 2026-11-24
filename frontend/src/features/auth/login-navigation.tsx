import { useEffect, type ComponentProps } from 'react'
import { Link, useNavigate } from 'react-router'
import { beginLogin, rememberLoginReturn } from './return-path'
import { StatePanel } from '@/shared/ui/state-panel'

export function LoginLink({
  returnTo,
  onClick,
  ...props
}: Omit<ComponentProps<typeof Link>, 'to'> & { returnTo: string }) {
  return (
    <Link
      {...props}
      to="/login"
      onClick={(event) => {
        onClick?.(event)
        if (!event.defaultPrevented) rememberLoginReturn(returnTo)
      }}
    />
  )
}

export function LoginRedirect({ returnTo }: { returnTo: string }) {
  const navigate = useNavigate()
  useEffect(() => {
    void navigate(beginLogin(returnTo), { replace: true })
  }, [navigate, returnTo])
  return <StatePanel title="로그인 확인 중…" />
}
