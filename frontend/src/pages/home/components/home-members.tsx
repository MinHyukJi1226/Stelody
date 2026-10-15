import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { homeMembersOptions } from '@/features/members/queries'
import { MemberChip } from '@/features/members/member-chip'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { HomeSection } from './home-section'

export function HomeMembers() {
  const query = useInfiniteQuery(homeMembersOptions)
  const members = [
    ...new Map(
      query.data?.pages.flatMap(({ items }) => items).map((m) => [m.id, m]),
    ).values(),
  ]
  return (
    <HomeSection
      title="목소리로 찾아보기"
      action={
        <Button asChild>
          <Link to="/members">모든 멤버</Link>
        </Button>
      }
    >
      {query.isPending ? (
        <p role="status" className="text-muted">
          멤버 불러오는 중…
        </p>
      ) : query.isError && !query.data ? (
        <StatePanel
          title="멤버를 불러오지 못했습니다"
          onRetry={() => void query.refetch()}
        />
      ) : members.length === 0 ? (
        <StatePanel title="등록된 멤버가 없습니다" />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-5">
            {members.map((member) => (
              <MemberChip key={member.id} member={member} />
            ))}
          </div>
          {query.isError && query.data && (
            <StatePanel
              title="멤버를 더 불러오지 못했습니다"
              onRetry={() =>
                void (query.isFetchNextPageError
                  ? query.fetchNextPage()
                  : query.refetch())
              }
            />
          )}
          {query.hasNextPage && !query.isError && (
            <Button
              disabled={query.isFetching}
              onClick={() => void query.fetchNextPage()}
            >
              {query.isFetching ? '불러오는 중…' : '멤버 더 보기'}
            </Button>
          )}
        </>
      )}
    </HomeSection>
  )
}
