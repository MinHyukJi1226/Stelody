import { Link } from 'react-router'
import googleMark from '@/shared/assets/google-mark.png'
import { Button } from '@/shared/ui/button'
import { googleLoginUrl } from '@/features/auth/api'

export function LoginCard({
  returnTo,
  notice,
  cancelled = false,
  canContinue = false,
  retry,
}: {
  returnTo: string
  notice?: string | undefined
  cancelled?: boolean
  canContinue?: boolean
  retry?: (() => void) | undefined
}) {
  return (
    <section aria-labelledby="login-card-title" className="login-card">
      <h2 id="login-card-title" className="text-lg font-bold leading-[1.5]">
        Google 계정으로 로그인
      </h2>
      <p className="text-sm leading-[1.5] text-muted">
        처음 로그인하면 계정이 자동으로 생성돼요.
      </p>
      <a className="google-sign-in" href={googleLoginUrl(returnTo)}>
        <img src={googleMark} alt="" width={20} height={20} />
        <span>Google로 로그인</span>
      </a>
      {notice && (
        <div
          className={`rounded-lg p-3 text-sm leading-[1.5] ${cancelled ? 'bg-tint text-muted' : 'bg-rose text-error'}`}
          role={cancelled ? 'status' : 'alert'}
        >
          {notice}
        </div>
      )}
      {retry && <Button onClick={retry}>로그인 상태 다시 확인</Button>}
      {canContinue && (
        <Button asChild>
          <Link to={returnTo} replace>
            현재 계정으로 계속하기
          </Link>
        </Button>
      )}
      <p className="text-sm leading-[1.5] text-muted">
        즐겨찾기와 플레이리스트를 내 계정에 저장해요.
      </p>
    </section>
  )
}

export function LoginSkeleton() {
  return (
    <div
      className="login-card"
      role="status"
      aria-label="로그인 확인 중"
      aria-busy="true"
    >
      <div className="h-6 w-3/4 rounded-lg bg-line" />
      <div className="h-5 w-full rounded-lg bg-line" />
      <div className="h-5 w-[70%] rounded-lg bg-line" />
      <div className="h-12 w-full rounded-lg bg-line" />
      <p className="text-sm leading-[1.5] text-muted">
        Google 로그인 결과를 확인하고 있어요.
      </p>
    </div>
  )
}
