import { useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { Button } from '@/shared/ui/button'
import type { CatalogSort } from './catalog-api'

const sortLabels: Record<CatalogSort, string> = {
  LATEST: '최신순',
  OLDEST: '오래된순',
  VIEWS: '인기순',
}
export function CatalogSortMenu({
  sort,
  onChange,
}: {
  sort: CatalogSort
  onChange: (sort: CatalogSort) => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button
          size="icon"
          className="border-0 bg-transparent"
          aria-label={`정렬 선택: ${sortLabels[sort]}`}
        >
          <svg
            width="22"
            height="22"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M4 4h16l-6.5 7.5V19l-3-1.5v-6L4 4Z"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinejoin="round"
            />
          </svg>
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="end"
          sideOffset={8}
          className="z-50 min-w-36 rounded-xl border border-line bg-surface p-2 text-ink shadow-lg"
        >
          <div
            role="menu"
            aria-label="곡 정렬"
            className="grid gap-1"
            onKeyDown={(event) => {
              const buttons = Array.from(
                event.currentTarget.querySelectorAll<HTMLButtonElement>(
                  'button',
                ),
              )
              const index = buttons.indexOf(
                document.activeElement as HTMLButtonElement,
              )
              const target =
                event.key === 'ArrowDown'
                  ? (index + 1) % buttons.length
                  : event.key === 'ArrowUp'
                    ? (index + buttons.length - 1) % buttons.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? buttons.length - 1
                        : null
              if (target !== null) {
                event.preventDefault()
                buttons[target]?.focus()
              }
            }}
          >
            {(Object.entries(sortLabels) as [CatalogSort, string][]).map(
              ([value, label]) => (
                <Button
                  key={value}
                  role="menuitemradio"
                  aria-checked={sort === value}
                  className={`min-h-11 justify-start px-3 py-2 ${sort === value ? 'bg-tint text-brand' : ''}`}
                  onClick={() => {
                    onChange(value)
                    setOpen(false)
                  }}
                >
                  {label}
                </Button>
              ),
            )}
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
