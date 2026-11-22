import { Link } from 'react-router'
import googleMark from '@/shared/assets/google-mark.png'
import logo from '@/shared/assets/logo.svg'
import { Button } from '@/shared/ui/button'
import { googleLoginUrl } from '@/features/auth/api'

export function LoginCard({
  returnTo,
  notice,
  cancelled = false,
  canContinue = false,
  retry,
  onContinue,
}: {
  returnTo: string
  notice?: string | undefined
  cancelled?: boolean
  canContinue?: boolean
  retry?: (() => void) | undefined
  onContinue?: () => void
}) {
  return (
    <div className="login-card">
      <img
        src={logo}
        alt=""
        width={52}
        height={52}
        className="self-center rounded-xl bg-brand-fill"
      />
      <h1
        id="login-title"
        className="mt-6 text-[28px] font-bold leading-[1.25] md:text-[32px]"
      >
        로그인
      </h1>
      <p className="mt-4 text-base leading-[1.5]">
        즐겨찾기와 플레이리스트 저장으로
        <br />내 취향 곡을 모아보세요
      </p>
      <a className="google-sign-in mt-8" href={googleLoginUrl(returnTo)}>
        <img src={googleMark} alt="" width={20} height={20} />
        <span>Google로 로그인</span>
      </a>
      {notice && (
        <div
          className={`mt-4 rounded-lg p-3 text-sm leading-[1.5] ${cancelled ? 'bg-tint text-muted' : 'bg-rose text-error'}`}
          role={cancelled ? 'status' : 'alert'}
        >
          {notice}
        </div>
      )}
      {retry && (
        <Button className="mt-4" onClick={retry}>
          로그인 상태 다시 확인
        </Button>
      )}
      {canContinue && (
        <Button asChild className="mt-4">
          <Link to={returnTo} replace onClick={onContinue}>
            현재 계정으로 계속하기
          </Link>
        </Button>
      )}
    </div>
  )
}

export function LoginSkeleton() {
  return (
    <div
      className="login-card gap-4"
      role="status"
      aria-label="로그인 확인 중"
      aria-busy="true"
    >
      <div className="size-[52px] self-center rounded-xl bg-line" />
      <div className="mt-2 h-10 w-24 self-center rounded-lg bg-line" />
      <div className="h-12 w-3/4 self-center rounded-lg bg-line" />
      <div className="mt-4 h-[52px] w-full rounded-xl bg-line" />
      <p className="text-sm leading-[1.5] text-muted">
        Google 로그인 결과를 확인하고 있어요.
      </p>
    </div>
  )
}
