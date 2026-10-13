import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <main className="mx-auto max-w-5xl px-6 py-16">
      <h1 className="text-2xl font-bold">페이지를 찾을 수 없습니다</h1>
      <Link className="mt-6 inline-flex min-h-11 items-center underline" to="/">
        홈으로 돌아가기
      </Link>
    </main>
  )
}
