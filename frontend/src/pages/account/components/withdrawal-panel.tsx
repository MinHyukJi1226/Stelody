import { Link } from 'react-router'
import googleMark from '@/shared/assets/google-mark.png'
import { Button } from '@/shared/ui/button'
import { loginPath } from '@/features/auth/return-path'
import { accountPath, withdrawalPath } from '@/features/account/api'
import { youtubePath } from '@/features/youtube/api'
import { AccountCard, AccountEmail, AccountSkeleton } from './account-layout'
import { withdrawalViews, type WithdrawalView } from '../withdrawal-view'

export function WithdrawalPanel({
  view,
  email,
  busy,
  onAuthorize,
  onWithdraw,
  onCheck,
  jobPath,
}: {
  view: WithdrawalView
  email?: string
  busy: boolean
  onAuthorize?: () => void
  onWithdraw?: () => void
  onCheck?: (() => void) | undefined
  jobPath?: string | undefined
}) {
  if (view === 'loading')
    return (
      <AccountCard loading>
        <p className="font-medium text-brand">계정 정보 조회 중</p>
        <h2 className="text-lg font-bold">계정 정보를 확인하고 있어요</h2>
        <AccountSkeleton />
        <p className="text-muted">잠시 기다려 주세요.</p>
      </AccountCard>
    )
  const content = withdrawalViews[view as keyof typeof withdrawalViews]
  if (!content) return null
  const identity = [
    'guide',
    'verified',
    'cancelled',
    'failed',
    'wrong',
    'expired',
    'starting',
  ].includes(view)
  const authorization = [
    'guide',
    'cancelled',
    'failed',
    'wrong',
    'expired',
    'starting',
  ].includes(view)
  const checking = ['checkingDelete', 'checkingOutcome'].includes(view)
  const home = (primary = false) => (
    <Button variant={primary ? 'primary' : 'secondary'} asChild>
      <Link to="/">홈으로</Link>
    </Button>
  )
  const cancel = (
    <Button asChild>
      <Link to={accountPath}>취소</Link>
    </Button>
  )
  return (
    <AccountCard loading={busy}>
      <p
        className={`font-medium ${{ brand: 'text-brand', green: 'text-green', warning: 'text-warning', error: 'text-error' }[content.tone]}`}
      >
        {content.label}
      </p>
      <h2 className="text-lg font-bold">{content.title}</h2>
      {checking && <AccountSkeleton />}
      <p className="w-full text-muted">{content.body}</p>
      {identity && email && <AccountEmail email={email} />}
      {view === 'verified' && (
        <p className="font-medium text-green">✓ Google 본인 확인 완료</p>
      )}
      {((identity && view !== 'starting') || view === 'submitting') && (
        <div className="flex w-full flex-col gap-3 rounded-xl bg-rose p-4">
          <p>
            계정·즐겨찾기·사이트 플레이리스트가 삭제되며 복구할 수 없습니다.
          </p>
          <p className="text-muted">
            YouTube 연결은 해제되지만 이미 생성한 YouTube 목록은 유지됩니다.
          </p>
        </div>
      )}
      {view === 'completed' && (
        <p className="text-muted">
          YouTube 연결은 해제되었지만 이미 생성된 YouTube 목록은 유지됩니다.
        </p>
      )}
      <div
        className={`flex w-full flex-wrap items-center gap-2 ${authorization ? 'flex-col items-start md:flex-row md:items-center' : ''}`}
      >
        {authorization && (
          <button
            className="google-sign-in w-full disabled:opacity-40 md:w-[302px]"
            disabled={busy}
            onClick={onAuthorize}
          >
            <img src={googleMark} alt="" width={20} height={20} />
            <span>
              {view === 'starting'
                ? 'Google 재인증 중…'
                : view === 'wrong'
                  ? 'Google 계정 다시 선택'
                  : 'Google 재인증'}
            </span>
          </button>
        )}
        {['verified', 'expired'].includes(view) && (
          <Button
            variant="destructive"
            disabled={busy || view === 'expired'}
            onClick={onWithdraw}
          >
            회원 탈퇴
          </Button>
        )}
        {view === 'submitting' && (
          <>
            <Button variant="destructive" disabled>
              탈퇴 처리 중…
            </Button>
            <Button disabled>취소</Button>
          </>
        )}
        {identity && cancel}
        {view === 'completed' && home(true)}
        {view === 'session' && (
          <>
            <Button variant="primary" asChild>
              <Link to={loginPath(withdrawalPath)}>다시 로그인</Link>
            </Button>
            {home()}
          </>
        )}
        {view === 'youtube' && (
          <>
            <Button asChild>
              <Link to={jobPath || '/library/playlists'}>
                YouTube 작업 확인
              </Link>
            </Button>
            <Button variant="primary" disabled={busy} onClick={onCheck}>
              상태 다시 확인
            </Button>
            {cancel}
          </>
        )}
        {view === 'revoking' && (
          <>
            <Button variant="primary" disabled={busy} onClick={onCheck}>
              처리 상태 확인
            </Button>
            <Button asChild>
              <Link
                to={`${youtubePath}?${new URLSearchParams({ returnTo: withdrawalPath })}`}
              >
                YouTube 연결 관리
              </Link>
            </Button>
          </>
        )}
        {['unknown', 'server', 'checkingOutcome', 'unavailable'].includes(
          view,
        ) && (
          <>
            <Button
              variant="primary"
              disabled={busy || !onCheck}
              onClick={onCheck}
            >
              {view === 'checkingOutcome' ? '결과 확인 중…' : '탈퇴 결과 확인'}
            </Button>
            {view !== 'checkingOutcome' && home()}
          </>
        )}
        {view === 'storage' && (
          <>
            <Button variant="primary" disabled={busy} onClick={onCheck}>
              다시 확인
            </Button>
            {cancel}
          </>
        )}
        {view === 'rejected' && (
          <Button asChild>
            <Link to={accountPath}>계정 관리로 돌아가기</Link>
          </Button>
        )}
        {view === 'checkingDelete' && (
          <>
            <Button disabled>상태 확인 중…</Button>
            {cancel}
          </>
        )}
      </div>
    </AccountCard>
  )
}
