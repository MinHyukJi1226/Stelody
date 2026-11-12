import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Form, Link, useLocation } from 'react-router'
import { MenuIcon } from '@/app/layout/menu-icon'
import { accountPath } from '@/features/account/api'
import { AdminLink } from '@/features/admin/admin-link'
import { AccountControl } from '@/features/auth/account-control'
import { loginPath } from '@/features/auth/return-path'
import { sessionOptions } from '@/features/auth/session'
import logo from '@/shared/assets/logo.svg'
import type { Theme } from '@/shared/lib/theme'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { Input } from '@/shared/ui/input'

export function HomeHeader({
  theme,
  toggleTheme,
}: {
  theme: Theme
  toggleTheme: () => void
}) {
  const location = useLocation()
  const search = location.pathname === '/search'
  const catalog = location.pathname === '/songs'
  const members =
    location.pathname === '/members' ||
    location.pathname.startsWith('/members/')
  const query = search
    ? (new URLSearchParams(location.search).get('q') ?? '')
    : ''
  const session = useQuery(sessionOptions)
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  const themeLabel =
    theme === 'dark' ? '라이트 모드로 변경' : '다크 모드로 변경'

  return (
    <header
      className={`grid w-full grid-cols-[1fr_auto_auto] items-center gap-3 bg-surface px-5 py-4 md:grid-cols-[140px_minmax(0,1fr)_auto_auto] md:gap-4 ${search || catalog || members ? 'md:px-8 md:py-[28.5px]' : 'md:px-10'}`}
    >
      <Link
        to="/"
        aria-label="Stelody 홈"
        className="flex items-center gap-3 text-lg font-bold"
      >
        <img
          src={logo}
          alt=""
          width={32}
          height={32}
          className="rounded-lg bg-brand-fill"
        />
        Stelody
      </Link>
      <Form
        key={location.pathname + location.search}
        action="/search"
        role="search"
        aria-label="노래 검색"
        className="col-span-full row-start-2 flex min-w-0 gap-2 md:col-span-1 md:row-start-1 md:col-start-2"
      >
        <label htmlFor="home-search" className="sr-only">
          곡명, 원곡, 멤버 검색
        </label>
        <Input
          id="home-search"
          name="q"
          defaultValue={query}
          type="search"
          maxLength={200}
          placeholder="곡명, 원곡, 멤버 검색"
          className="h-11 min-h-11 min-w-0 flex-1 rounded-lg px-3 py-0 text-sm"
          required
          pattern={'.*\\S.*'}
        />
        <Button
          variant="primary"
          type="submit"
          className={`h-11 min-h-11 w-16 py-0 ${theme === 'dark' ? 'bg-brand text-night' : ''}`}
        >
          검색
        </Button>
      </Form>
      {session.isPending ? (
        <Button disabled className="h-11 min-h-11 px-3 py-0">
          확인 중…
        </Button>
      ) : (
        <Button asChild className="h-11 min-h-11 px-3 py-0">
          <Link
            to={
              session.data
                ? accountPath
                : loginPath(location.pathname + location.search + location.hash)
            }
          >
            {session.data ? '내 계정' : '로그인'}
          </Link>
        </Button>
      )}
      <Button
        size="icon"
        className="border-0 bg-transparent"
        aria-label="메뉴 열기"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <MenuIcon />
      </Button>
      {open && (
        <Dialog
          labelledBy="home-menu-title"
          onDismiss={close}
          dismissOnBackdrop
          className="fixed inset-y-0 right-0 left-auto m-0 flex h-dvh max-h-none w-[min(320px,100vw)] flex-col gap-4 rounded-none border-0 p-6"
        >
          <div className="flex items-center justify-between">
            <h2 id="home-menu-title" className="text-lg font-bold">
              메뉴
            </h2>
            <Button onClick={close}>닫기</Button>
          </div>
          <nav aria-label="주 메뉴" className="grid gap-2">
            <Button asChild>
              <Link to="/songs" onClick={close}>
                전체곡
              </Link>
            </Button>
            <Button asChild>
              <Link to="/members" onClick={close}>
                멤버
              </Link>
            </Button>
            {session.data && (
              <>
                <Button asChild>
                  <Link to="/library" onClick={close}>
                    좋아하는 곡
                  </Link>
                </Button>
                <Button asChild>
                  <Link to="/library/playlists" onClick={close}>
                    플레이리스트
                  </Link>
                </Button>
                <AdminLink onNavigate={close} />
              </>
            )}
            <Button onClick={toggleTheme}>{themeLabel}</Button>
          </nav>
          {session.data && <AccountControl onNavigate={close} />}
        </Dialog>
      )}
    </header>
  )
}
