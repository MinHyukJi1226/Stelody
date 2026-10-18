import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { memberSongsOptions } from '@/features/members/queries'
import {
  defaultMemberSongFilters,
  memberSongParams,
  readMemberSongFilters,
  type MemberSongFilters,
} from '@/features/members/song-filters'
import { uniqueSongs } from '@/features/songs/queries'
import { SongGrid, SongGridSkeleton } from '@/features/songs/song-card'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Tabs, TabsContent } from '@/shared/ui/tabs'
import { MemberSongControls } from './member-song-controls'
import { StatePanel } from '@/shared/ui/state-panel'
import { MemberState } from './member-state'

export function MemberSongs({
  memberId,
  memberName,
}: {
  memberId: string
  memberName: string
}) {
  const [params, setParams] = useSearchParams()
  const parsed = readMemberSongFilters(params)
  const filters = parsed.success ? parsed.data : defaultMemberSongFilters
  const patch = (value: Partial<MemberSongFilters>) =>
    setParams(memberSongParams({ ...filters, ...value }), {
      preventScrollReset: true,
    })
  const client = useQueryClient()
  const options = memberSongsOptions(memberId, filters)
  const query = useInfiniteQuery({ ...options, enabled: parsed.success })
  const sentinel = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  useEffect(() => {
    const element = sentinel.current
    if (
      !element ||
      !hasNextPage ||
      isFetching ||
      isError ||
      !('IntersectionObserver' in window)
    )
      return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) void fetchNextPage()
      },
      { rootMargin: '200px' },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [hasNextPage, isFetching, isError, fetchNextPage])
  const songs = uniqueSongs(query.data?.pages ?? [])
  const changed =
    query.error instanceof ApiError &&
    query.error.status === 409 &&
    query.error.code === 'VIEW_PUBLICATION_CHANGED'
  const unavailable =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  const retry = () => {
    if (changed) {
      void client.resetQueries({ queryKey: options.queryKey, exact: true })
      return
    }
    void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch())
  }
  return (
    <section aria-label="멤버의 공개곡" className="min-w-0">
      <Tabs
        value={filters.type}
        onValueChange={(type) =>
          patch({ type: type as MemberSongFilters['type'] })
        }
        className="flex min-w-0 flex-col gap-8"
      >
        <MemberSongControls filters={filters} onChange={patch} />
        <TabsContent
          value={filters.type}
          className="mt-0 flex min-w-0 flex-col gap-8"
        >
          {!parsed.success ? (
            <StatePanel
              title="곡 조회 조건이 올바르지 않습니다"
              description="필터를 다시 선택하거나 초기화해 주세요."
              onRetry={() =>
                setParams(new URLSearchParams(), { preventScrollReset: true })
              }
            />
          ) : unavailable ? (
            <MemberState
              kind="unavailable"
              title="공개 곡을 볼 수 없습니다"
              description="멤버의 공개 상태를 확인할 수 없습니다."
            />
          ) : query.isPending ? (
            <SongGridSkeleton />
          ) : query.isError && !query.data ? (
            <MemberState
              kind="error"
              title="노래를 불러오지 못했어요"
              description="멤버 정보는 유지됩니다. 잠시 후 다시 시도해 주세요."
              onRetry={retry}
            />
          ) : songs.length === 0 ? (
            <MemberState
              kind="empty"
              title={
                filters.type === 'ALL' && !filters.collaboration
                  ? '등록된 공개 곡이 없습니다'
                  : '조건에 맞는 공개 곡이 없습니다'
              }
              description={
                filters.type === 'ALL' && !filters.collaboration
                  ? '새 노래가 등록되면 이곳에서 만나보세요.'
                  : '곡 유형이나 공동 참여 필터를 바꿔보세요.'
              }
              detail={`${memberName} · ${query.data?.pages[0]?.totalCount ?? 0}곡`}
            />
          ) : (
            <SongGrid songs={songs} />
          )}
          {parsed.success && !unavailable && query.isError && query.data && (
            <StatePanel
              title={
                query.isFetchNextPageError
                  ? '노래를 더 불러오지 못했습니다'
                  : '노래를 갱신하지 못했습니다'
              }
              description={
                changed
                  ? '조회수 집계가 갱신되었습니다. 다시 시도하면 첫 페이지부터 불러옵니다.'
                  : '이미 불러온 목록은 유지됩니다.'
              }
              onRetry={retry}
            />
          )}
          {parsed.success && !unavailable && (
            <div ref={sentinel}>
              {query.hasNextPage && !query.isError && (
                <Button
                  disabled={query.isFetching}
                  onClick={() => void query.fetchNextPage()}
                >
                  {query.isFetchingNextPage
                    ? '노래 더 불러오는 중…'
                    : '노래 더 보기'}
                </Button>
              )}
              {!query.hasNextPage && songs.length > 0 && (
                <p role="status" className="text-sm text-muted">
                  모든 노래를 불러왔습니다.
                </p>
              )}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </section>
  )
}
