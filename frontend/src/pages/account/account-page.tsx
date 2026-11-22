import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { accountPath, withdrawalPath } from '@/features/account/api'
import { Button } from '@/shared/ui/button'
import {
  AccountEmail,
  AccountLayout,
  AccountSkeleton,
} from './components/account-layout'
import { AccountYouTubeConnection } from './components/account-youtube-connection'

export function AccountPage() {
  const session = useQuery(sessionOptions)
  return (
    <AccountLayout title="계정 관리">
      <section
        aria-label="계정 상태"
        aria-busy={session.isPending}
        className="flex w-full min-w-0 flex-col items-start gap-12 text-sm leading-[1.5]"
      >
        {session.isPending ? (
          <div className="flex w-full max-w-[640px] flex-col gap-3">
            <p className="font-medium text-brand">계정 정보 조회 중</p>
            <h2 className="text-lg font-bold">계정 정보를 확인하고 있어요</h2>
            <AccountSkeleton />
            <p className="text-muted">잠시 기다려 주세요.</p>
          </div>
        ) : session.isError ? (
          <div className="flex flex-col items-start gap-3">
            <p className="font-medium text-error">계정 정보 조회 오류</p>
            <h2 className="text-lg font-bold">계정 정보를 불러오지 못했어요</h2>
            <p className="text-muted">잠시 후 다시 시도해 주세요.</p>
            <Button variant="primary" onClick={() => void session.refetch()}>
              다시 시도
            </Button>
          </div>
        ) : !session.data ? (
          <div className="flex flex-col items-start gap-3">
            <h2 className="text-lg font-bold">다시 로그인해 주세요</h2>
            <Button variant="primary" asChild>
              <Link to={loginPath(accountPath)}>다시 로그인</Link>
            </Button>
          </div>
        ) : (
          <>
            <AccountEmail email={session.data.email} />
            <AccountYouTubeConnection
              key={session.data.id}
              user={session.data.id}
            />
            <div className="flex w-full flex-col items-start gap-3">
              <h2 className="text-lg font-bold md:text-xl">회원 탈퇴</h2>
              <p className="text-muted">
                회원 탈퇴시 즐겨찾기한 곡과 플레이리스트 정보가 소실됩니다.
              </p>
              <Button variant="destructive" asChild>
                <Link to={withdrawalPath}>회원 탈퇴 하기</Link>
              </Button>
            </div>
          </>
        )}
      </section>
    </AccountLayout>
  )
}
