import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { apiClient } from '@/shared/api/client'
import { sessionOptions } from '@/features/auth/session'
import { safeReturnPath } from '@/features/auth/return-path'
import { SiteFooter } from '@/shared/ui/site-footer'
import { LoginCard, LoginSkeleton } from './components/login-card'
import { LoginReturn } from './components/login-return'
import { preparePendingAction } from '@/features/auth/pending-action'

export function LoginPage() {
  const [params, setParams] = useSearchParams()
  const returnTo = safeReturnPath(params.get('returnTo'))
  const [result] = useState(() => params.get('loginResult'))
  const session = useQuery(sessionOptions)
  useEffect(() => {
    if (result === null) return
    apiClient.invalidateCsrf()
  }, [result])
  useEffect(() => {
    if (!params.has('loginResult')) return
    const clean = new URLSearchParams(params)
    clean.delete('loginResult')
    setParams(clean, { replace: true })
  }, [params, setParams])

  const checking = session.isPending || session.isFetching
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
    <div className="login-page flex min-w-0 flex-col">
      <section
        className="flex flex-1 flex-col gap-4"
        aria-labelledby="login-title"
      >
        {checking && (
          <h1 id="login-title" className="sr-only">
            로그인 확인 중
          </h1>
        )}
        {!checking && (
          <>
            <p className="text-xs leading-[1.5] text-brand">YOUR ARCHIVE</p>
            <h1
              id="login-title"
              className="text-[32px] font-bold leading-[1.5]"
            >
              나만의 노래를<span className="block md:inline"> 모아보세요</span>
            </h1>
            <p className="text-muted">
              Google 계정으로 나만의 노래를 모아보세요.
            </p>
          </>
        )}
        <div
          className={
            checking
              ? 'flex flex-1 items-center justify-center'
              : 'flex justify-center'
          }
        >
          {checking ? (
            <LoginSkeleton />
          ) : (
            <LoginCard
              returnTo={returnTo}
              notice={notice}
              cancelled={result === 'cancelled' && !session.isError}
              canContinue={!!session.data && !session.isError}
              onContinue={() => {
                if (session.data)
                  preparePendingAction(session.data.id, returnTo)
              }}
              retry={session.isError ? () => void session.refetch() : undefined}
            />
          )}
        </div>
      </section>
      <SiteFooter className="min-h-[124px] p-4 px-5 text-sm leading-[1.5] md:min-h-[75px] md:p-4 md:px-10" />
    </div>
  )
}
