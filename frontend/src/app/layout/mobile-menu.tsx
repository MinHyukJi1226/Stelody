import { useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { NavLink } from 'react-router'
import { Button } from '@/shared/ui/button'
import { MenuIcon } from './menu-icon'
import { AccountControl } from '@/features/auth/account-control'

export default function MobileMenu({
  navigation,
}: {
  navigation: readonly { to: string; label: string }[]
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button
          size="icon"
          className="mobile-menu-trigger border-0 bg-transparent"
          aria-label="메뉴 열기"
        >
          <MenuIcon />
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          aria-label="사이트 메뉴"
          align="end"
          sideOffset={8}
          className="mobile-menu z-50 rounded-xl border border-line bg-surface p-3 text-ink shadow-lg"
        >
          <nav aria-label="모바일 주 메뉴" className="flex flex-col gap-2">
            {navigation.map(({ to, label }) => (
              <Button key={to} asChild>
                <NavLink to={to} onClick={() => setOpen(false)}>
                  {label}
                </NavLink>
              </Button>
            ))}
            <AccountControl onNavigate={() => setOpen(false)} />
          </nav>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
