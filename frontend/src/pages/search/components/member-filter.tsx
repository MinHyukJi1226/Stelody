import * as Popover from '@radix-ui/react-popover'
import { useMemberDirectoryQuery } from '@/features/members/use-member-directory'
import { Button } from '@/shared/ui/button'

export function MemberFilter({
  selected,
  onChange,
}: {
  selected: string[]
  onChange: (ids: string[]) => void
}) {
  const query = useMemberDirectoryQuery()
  const members = [
    ...new Map(
      query.data?.pages.flatMap(({ items }) => items).map((m) => [m.id, m]),
    ).values(),
  ]
  const label = selected.length
    ? selected
        .map((id) => members.find((m) => m.id === id)?.name ?? '선택한 멤버')
        .join(' · ')
    : '전체 멤버'
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <span id="search-members-label" className="text-sm font-medium">
        멤버
      </span>
      <Popover.Root>
        <Popover.Trigger asChild>
          <Button
            aria-labelledby="search-members-label search-members-value"
            className="min-h-14 w-full justify-start whitespace-normal border border-line text-left text-base font-normal"
          >
            <span id="search-members-value" className="break-words text-muted">
              {label}
            </span>
          </Button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            aria-label="멤버 선택"
            align="start"
            sideOffset={8}
            className="z-50 flex max-h-[min(400px,70dvh)] w-[min(350px,calc(100vw-40px))] flex-col gap-2 overflow-y-auto rounded-xl border border-line bg-surface p-4 text-ink shadow-lg"
          >
            <Button onClick={() => onChange([])}>전체 멤버</Button>
            {query.isPending && (
              <p role="status" className="text-sm text-muted">
                멤버 불러오는 중…
              </p>
            )}
            {members.map((m) => (
              <label
                key={m.id}
                className="flex min-h-11 cursor-pointer items-center gap-3 text-sm"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(m.id)}
                  disabled={selected.length >= 20 && !selected.includes(m.id)}
                  onChange={(e) =>
                    onChange(
                      e.target.checked
                        ? [...selected, m.id]
                        : selected.filter((id) => id !== m.id),
                    )
                  }
                  className="size-4 accent-brand"
                />
                {m.name}
                {m.activityStatus === 'GRADUATED' && ' (졸업)'}
              </label>
            ))}
            {query.isError && (
              <div role="status" className="text-sm text-muted">
                멤버를 불러오지 못했습니다.
                <Button
                  onClick={() =>
                    void (query.isFetchNextPageError
                      ? query.fetchNextPage()
                      : query.refetch())
                  }
                >
                  다시 시도
                </Button>
              </div>
            )}
            {!query.isPending && !query.isError && members.length === 0 && (
              <p className="text-sm text-muted">등록된 멤버가 없습니다.</p>
            )}
            {query.hasNextPage && !query.isError && (
              <p role="status" className="text-sm text-muted">
                멤버를 더 불러오는 중…
              </p>
            )}
            <Popover.Close asChild>
              <Button>선택 완료</Button>
            </Popover.Close>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </div>
  )
}
