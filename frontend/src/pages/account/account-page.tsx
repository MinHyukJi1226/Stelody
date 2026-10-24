import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { accountPath, withdrawalPath } from '@/features/account/api'
import { youtubePath } from '@/features/youtube/api'
import { Button } from '@/shared/ui/button'
import {
  AccountCard,
  AccountEmail,
  AccountLayout,
  AccountSkeleton,
} from './components/account-layout'

export function AccountPage() {
  const session = useQuery(sessionOptions)
  return (
    <AccountLayout title="계정 관리">
      <AccountCard loading={session.isPending}>
        {session.isPending ? (
          <>
            <p className="font-medium text-brand">계정 정보 조회 중</p>
            <h2 className="text-lg font-bold">계정 정보를 확인하고 있어요</h2>
            <AccountSkeleton />
            <p className="text-muted">잠시 기다려 주세요.</p>
          </>
        ) : session.isError ? (
          <>
            <p className="font-medium text-error">계정 정보 조회 오류</p>
            <h2 className="text-lg font-bold">계정 정보를 불러오지 못했어요</h2>
            <p className="text-muted">잠시 후 다시 시도해 주세요.</p>
            <Button variant="primary" onClick={() => void session.refetch()}>
              다시 시도
            </Button>
          </>
        ) : !session.data ? (
          <>
            <h2 className="text-lg font-bold">다시 로그인해 주세요</h2>
            <Button variant="primary" asChild>
              <Link to={loginPath(accountPath)}>다시 로그인</Link>
            </Button>
          </>
        ) : (
          <>
            <p className="font-medium text-brand">내 계정</p>
            <AccountEmail email={session.data.email} />
            <div className="flex w-full flex-col items-start gap-3">
              <h2 className="text-lg font-bold">YouTube 연결</h2>
              <p className="text-muted">
                플레이리스트 내보내기에 사용할 YouTube 연결을 관리합니다.
              </p>
              <Button asChild>
                <Link
                  to={`${youtubePath}?${new URLSearchParams({ returnTo: accountPath })}`}
                >
                  YouTube 연결 관리
                </Link>
              </Button>
            </div>
            <div className="flex w-full flex-col items-start gap-3 border-t border-line pt-5">
              <h2 className="text-lg font-bold">회원 탈퇴</h2>
              <p className="text-muted">
                탈퇴 전 삭제되는 정보와 유의 사항을 확인해 주세요.
              </p>
              <Button variant="destructive" asChild>
                <Link to={withdrawalPath}>회원 탈퇴 안내</Link>
              </Button>
            </div>
          </>
        )}
      </AccountCard>
    </AccountLayout>
  )
}
