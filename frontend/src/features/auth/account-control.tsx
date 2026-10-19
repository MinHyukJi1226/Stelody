import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router'
import { Button } from '@/shared/ui/button'
import { logout } from './api'
import { loginPath } from './return-path'
import { clearPrivateQueries, endLocalSession, sessionOptions } from './session'

export function AccountControl({ onNavigate }: { onNavigate?: () => void }) {
  const client = useQueryClient()
  const session = useQuery(sessionOptions)
  const location = useLocation()
  const mutation = useMutation({
    mutationFn: logout,
    onMutate: async () => {
      await client.cancelQueries({ queryKey: sessionOptions.queryKey })
      await clearPrivateQueries(client)
    },
    onSuccess: async () => {
      await endLocalSession(client)
      onNavigate?.()
    },
    onError: async () => {
      // A lost response may follow successful server-side logout; verify rather than guess.
      await client.invalidateQueries({ queryKey: sessionOptions.queryKey })
    },
  })

  return (
    <div className="flex flex-col items-center justify-center gap-2">
      {session.isPending ? (
        <Button disabled className="h-11 min-h-11 w-[84px] px-3 py-0 leading-5">
          확인 중…
        </Button>
      ) : session.data ? (
        <Button
          className="h-11 min-h-11 w-[84px] px-3 py-0 leading-5"
          disabled={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? '로그아웃 중…' : '로그아웃'}
        </Button>
      ) : (
        <Button asChild>
          <Link
            onClick={onNavigate}
            to={loginPath(
              `${location.pathname}${location.search}${location.hash}`,
            )}
          >
            로그인
          </Link>
        </Button>
      )}
      {mutation.isError && (
        <p role="alert" className="max-w-48 text-xs text-error">
          로그아웃 결과를 확인하지 못했습니다. 다시 시도해 주세요.
        </p>
      )}
    </div>
  )
}
