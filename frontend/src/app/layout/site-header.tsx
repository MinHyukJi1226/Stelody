import { lazy, Suspense } from 'react'
import { Link, NavLink } from 'react-router'
import logo from '@/shared/assets/logo.svg'
import sun from '@/shared/assets/sun.svg'
import moon from '@/shared/assets/moon.svg'
import type { Theme } from '@/shared/lib/theme'
import { useMediaQuery } from '@/shared/lib/use-media-query'
import { Button } from '@/shared/ui/button'
import { MenuIcon } from './menu-icon'
import { AccountControl } from '@/features/auth/account-control'
import { AccountLink } from '@/features/account/account-link'
import { AdminLink } from '@/features/admin/admin-link'

const navigation = [
  { to: '/search', label: '검색' },
  { to: '/members', label: '멤버' },
  { to: '/library', label: '내 보관함' },
]
const MobileMenu = lazy(() => import('./mobile-menu'))

export function SiteHeader({
  theme,
  toggleTheme,
}: {
  theme: Theme
  toggleTheme: () => void
}) {
  const mobile = useMediaQuery('(width < 768px)')
  return (
    <header className="site-header">
      <Link
        to="/"
        className="site-brand flex items-center gap-2.5 rounded-lg"
        aria-label="Stelody 홈"
      >
        <span className="size-8 shrink-0 rounded-lg bg-brand-fill">
          <img src={logo} alt="" width={32} height={32} />
        </span>
        <span className="text-xl font-bold leading-[1.5]">Stelody</span>
      </Link>
      <nav className="site-nav desktop-nav" aria-label="주 메뉴">
        {navigation.map(({ to, label }) => (
          <Button key={to} asChild>
            <NavLink to={to}>{label}</NavLink>
          </Button>
        ))}
        <AccountLink />
        <AdminLink />
        <AccountControl />
      </nav>
      <Button
        size="icon"
        onClick={toggleTheme}
        aria-label={
          theme === 'dark' ? '라이트 모드로 변경' : '다크 모드로 변경'
        }
      >
        <img
          src={theme === 'dark' ? sun : moon}
          alt=""
          width={24}
          height={24}
        />
      </Button>
      {mobile && (
        <Suspense
          fallback={
            <Button
              size="icon"
              className="mobile-menu-trigger border-0 bg-transparent"
              disabled
              aria-label="메뉴 불러오는 중"
            >
              <MenuIcon />
            </Button>
          }
        >
          <MobileMenu navigation={navigation} />
        </Suspense>
      )}
    </header>
  )
}
