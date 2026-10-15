import { Link } from 'react-router'

export function HomeFooter() {
  return (
    <footer className="-mx-5 -mb-12 bg-surface p-5 text-xs text-muted md:mx-[calc(clamp(24px,8.333333vw,120px)*-1)] md:p-10">
      <p>Stelody · 팬이 만드는 노래 아카이브</p>
      <nav aria-label="하단 메뉴" className="mt-2 flex gap-4 md:hidden">
        <Link className="inline-flex min-h-11 items-center" to="/">
          홈
        </Link>
        <Link className="inline-flex min-h-11 items-center" to="/search">
          검색
        </Link>
        <Link className="inline-flex min-h-11 items-center" to="/library">
          보관함
        </Link>
      </nav>
    </footer>
  )
}
