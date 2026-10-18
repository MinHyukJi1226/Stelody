import { useInfiniteQuery } from '@tanstack/react-query'
import { homeMembersOptions } from '@/features/members/queries'
import { MemberChip } from '@/features/members/member-chip'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { SiteFooter } from '@/shared/ui/site-footer'

export function MemberListPage() {
  const query = useInfiniteQuery(homeMembersOptions)
  const members = [
    ...new Map(
      query.data?.pages
        .flatMap(({ items }) => items)
        .map((member) => [member.id, member]),
    ).values(),
  ]
  const retry = () =>
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  return (
    <div className="flex min-h-[calc(100dvh-165px)] flex-col gap-8 md:min-h-[calc(100dvh-196px)]">
      <p className="text-xs leading-[1.5] text-brand">VOICES</p>
      <h1 className="text-[32px] font-bold leading-[1.5]">
        목소리로 찾아보세요
      </h1>
      <p className="text-base leading-[1.5] text-muted">
        좋아하는 멤버의 공개곡을 한곳에서 만나보세요.
      </p>
      {query.isPending ? (
        <div
          role="status"
          aria-label="멤버 불러오는 중"
          aria-busy="true"
          className="grid grid-cols-2 gap-x-2 gap-y-3 md:grid-cols-3 lg:grid-cols-5 lg:gap-x-3"
        >
          {Array.from({ length: 15 }, (_, i) => (
            <div
              key={i}
              aria-hidden="true"
              className="flex min-h-14 items-center gap-2.5 rounded-xl bg-surface p-3"
            >
              <div className="size-8 shrink-0 rounded-full bg-line" />
              <div className="h-4 w-2/3 rounded bg-line" />
            </div>
          ))}
        </div>
      ) : query.isError && !query.data ? (
        <StatePanel title="멤버를 불러오지 못했습니다" onRetry={retry} />
      ) : members.length === 0 ? (
        <StatePanel title="등록된 멤버가 없습니다" />
      ) : (
        <div
          className="grid grid-cols-2 gap-x-2 gap-y-3 md:grid-cols-3 lg:grid-cols-5 lg:gap-x-3"
          aria-label="모든 멤버"
        >
          {members.map((member) => (
            <MemberChip key={member.id} member={member} />
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
      <div className="mt-auto">
        <SiteFooter />
      </div>
    </div>
  )
}
