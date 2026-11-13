import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { homeMembersOptions } from '@/features/members/queries'
import type { MemberCard } from '@/shared/api/types'
import { Artwork } from '@/shared/ui/artwork'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'

function MemberPortrait({ member }: { member: MemberCard }) {
  return (
    <Link
      to={`/members/${member.id}`}
      className="grid w-24 min-w-0 justify-items-center gap-2 rounded-lg text-center text-xs leading-[1.5] transition-colors hover:text-brand focus-visible:text-brand"
    >
      <Artwork
        src={member.profileImageUrl}
        avatar
        className="size-[72px] rounded-full"
      />
      <span className="w-full break-words">
        {member.name}
        {member.activityStatus === 'GRADUATED' && (
          <span className="sr-only"> (졸업)</span>
        )}
      </span>
    </Link>
  )
}

export function MemberListPage() {
  const query = useInfiniteQuery(homeMembersOptions)
  const members = [
    ...new Map(
      query.data?.pages
        .flatMap(({ items }) => items)
        .map((member) => [member.id, member]),
    ).values(),
  ]
  const units = new Map<string | null, MemberCard[]>()
  for (const member of members) {
    if (member.activityStatus === 'GRADUATED') continue
    const unit = units.get(member.unitName) ?? []
    unit.push(member)
    units.set(member.unitName, unit)
  }
  const graduates = members.filter(
    (member) => member.activityStatus === 'GRADUATED',
  )
  const groups = [
    ...[...units].map(([name, items]) => ({
      key: `unit:${name}`,
      name,
      items,
    })),
    ...(graduates.length
      ? [{ key: 'graduates', name: 'GRADUATE', items: graduates }]
      : []),
  ]
  const retry = () =>
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <section
        aria-labelledby="member-list-title"
        className="flex min-w-0 flex-1 flex-col gap-6 px-5 py-8 md:px-8"
      >
        <h1
          id="member-list-title"
          className="home-section-enter text-2xl font-bold leading-[1.5]"
        >
          전체 멤버
        </h1>
        {query.isPending ? (
          <div
            role="status"
            aria-label="멤버 불러오는 중"
            aria-busy="true"
            className="grid grid-cols-[repeat(auto-fill,96px)] gap-4"
          >
            {Array.from({ length: 15 }, (_, i) => (
              <div
                key={i}
                aria-hidden="true"
                className="grid justify-items-center gap-2"
              >
                <div className="size-[72px] rounded-full bg-line" />
                <div className="h-[18px] w-20 rounded bg-line" />
              </div>
            ))}
          </div>
        ) : query.isError && !query.data ? (
          <StatePanel title="멤버를 불러오지 못했습니다" onRetry={retry} />
        ) : members.length === 0 ? (
          <StatePanel title="등록된 멤버가 없습니다" />
        ) : (
          <div
            className="home-section-enter flex min-w-0 flex-col gap-6"
            aria-label="모든 멤버"
          >
            {groups.map((group) => (
              <section
                key={group.key}
                aria-label={group.name ?? undefined}
                className="flex min-w-0 flex-col gap-4"
              >
                {group.name && (
                  <h2 className="text-lg font-bold leading-[1.5]">
                    {group.name}
                  </h2>
                )}
                <div className="grid grid-cols-[repeat(auto-fill,96px)] gap-x-4 gap-y-6">
                  {group.items.map((member) => (
                    <MemberPortrait key={member.id} member={member} />
                  ))}
                </div>
              </section>
            ))}
          </div>
        )}
        {query.data && query.isError && (
          <StatePanel
            title="멤버를 더 불러오지 못했습니다"
            description="이미 불러온 멤버 목록은 유지됩니다."
            onRetry={retry}
          />
        )}
        {query.hasNextPage && !query.isError && (
          <Button
            disabled={query.isFetching}
            className="self-start"
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetching ? '멤버 불러오는 중…' : '멤버 더 보기'}
          </Button>
        )}
      </section>
      <footer className="grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
