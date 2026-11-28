import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { apiClient } from '@/shared/api/client'
import { sessionOptions } from '@/features/auth/session'
import {
  clearLoginReturn,
  readLoginReturn,
  rememberLoginReturn,
  safeReturnPath,
} from '@/features/auth/return-path'
import { LoginCard, LoginSkeleton } from './components/login-card'
import { LoginReturn } from './components/login-return'
import { preparePendingAction } from '@/features/auth/pending-action'

export function LoginPage() {
  const [params, setParams] = useSearchParams()
  const [returnTo] = useState(() =>
    params.has('returnTo')
      ? safeReturnPath(params.get('returnTo'))
      : readLoginReturn(),
  )
  const [result] = useState(() => params.get('loginResult'))
  const session = useQuery(sessionOptions)
  useEffect(() => {
    if (result === null) return
    apiClient.invalidateCsrf()
  }, [result])
  useEffect(() => {
    if (!params.has('loginResult') && !params.has('returnTo')) return
    if (params.has('returnTo')) rememberLoginReturn(returnTo)
    const clean = new URLSearchParams(params)
    clean.delete('loginResult')
    clean.delete('returnTo')
    setParams(clean, { replace: true })
  }, [params, returnTo, setParams])

  const checking =
    session.isPending ||
    session.isFetching ||
    params.has('returnTo') ||
    params.has('loginResult')
  const failed =
    result !== null && result !== 'success' && result !== 'cancelled'
  if (
    !checking &&
    !session.isError &&
    session.data &&
    !failed &&
    result !== 'cancelled'
  ) {
    return <LoginReturn userId={session.data.id} returnTo={returnTo} />
  }
  const notice = session.isError
    ? '로그인 상태를 확인하지 못했어요. 잠시 후 다시 확인해 주세요.'
    : result === 'cancelled'
      ? '로그인이 취소되었어요. 다시 시도할 수 있어요.'
      : failed || (result === 'success' && !session.data)
        ? 'Google 로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.'
        : undefined

  return (
    <div className="login-page flex min-w-0 flex-1 flex-col px-5 md:px-8">
      <section
        className="flex flex-1 flex-col items-center pb-12 pt-[clamp(48px,12dvh,160px)]"
        aria-labelledby="login-title"
      >
        {checking && (
          <h1 id="login-title" className="sr-only">
            로그인 확인 중
          </h1>
        )}
        {checking ? (
          <LoginSkeleton />
        ) : (
          <LoginCard
            returnTo={returnTo}
            notice={notice}
            cancelled={result === 'cancelled' && !session.isError}
            canContinue={!!session.data && !session.isError}
            onContinue={() => {
              if (session.data) preparePendingAction(session.data.id, returnTo)
              clearLoginReturn()
            }}
            retry={session.isError ? () => void session.refetch() : undefined}
          />
        )}
      </section>
    </div>
  )
}
