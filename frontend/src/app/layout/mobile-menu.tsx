import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import * as Popover from '@radix-ui/react-popover'
import { NavLink } from 'react-router'
import { Button } from '@/shared/ui/button'
import { MenuIcon } from './menu-icon'
import { AccountControl } from '@/features/auth/account-control'
import { AccountLink } from '@/features/account/account-link'
import { AdminLink } from '@/features/admin/admin-link'
import { sessionOptions } from '@/features/auth/session'
import { Dialog } from '@/shared/ui/dialog'

export default function MobileMenu({
  navigation,
}: {
  navigation: readonly { to: string; label: string }[]
}) {
  const [open, setOpen] = useState(false)
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
  })
  if (session.data)
    return (
      <>
        <Button
          size="icon"
          className="mobile-menu-trigger border-0 bg-transparent"
          aria-label="메뉴 열기"
          aria-expanded={open}
          onClick={() => setOpen(true)}
        >
          <MenuIcon />
        </Button>
        {open && (
          <Dialog
            labelledBy="account-menu-title"
            onDismiss={() => setOpen(false)}
            dismissOnBackdrop
            className="fixed inset-y-0 right-0 left-auto m-0 ml-auto flex h-dvh max-h-none w-[min(320px,100vw)] max-w-[320px] flex-col gap-6 rounded-none border-0 p-6 backdrop:bg-black/40"
          >
            <div className="flex items-center justify-between">
              <h2 id="account-menu-title" className="text-lg font-bold">
                메뉴
              </h2>
              <Button onClick={() => setOpen(false)}>닫기</Button>
            </div>
            <nav aria-label="모바일 주 메뉴" className="flex flex-col gap-2">
              {navigation.map(({ to, label }) => (
                <Button key={to} asChild>
                  <NavLink to={to} onClick={() => setOpen(false)}>
                    {label}
                  </NavLink>
                </Button>
              ))}
              <AccountLink onNavigate={() => setOpen(false)} />
              <AdminLink mobile onNavigate={() => setOpen(false)} />
            </nav>
            <div className="mt-auto w-full">
              <AccountControl onNavigate={() => setOpen(false)} />
            </div>
          </Dialog>
        )}
      </>
    )
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
            <AccountLink onNavigate={() => setOpen(false)} />
            <AdminLink mobile onNavigate={() => setOpen(false)} />
            <AccountControl onNavigate={() => setOpen(false)} />
          </nav>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
