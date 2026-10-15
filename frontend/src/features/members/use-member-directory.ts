import { useEffect } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { homeMembersOptions } from './queries'

export function useMemberDirectory(enabled: boolean) {
  // Share the home member cache; only a complete roster can identify a full unit.
  const query = useInfiniteQuery({ ...homeMembersOptions, enabled })
  const { hasNextPage, isFetching, isError, fetchNextPage } = query
  useEffect(() => {
    if (enabled && hasNextPage && !isFetching && !isError) void fetchNextPage()
  }, [enabled, hasNextPage, isFetching, isError, fetchNextPage])
  const pages = query.data?.pages
  if (!pages?.length || pages[pages.length - 1]!.hasNext) return undefined
  return [
    ...new Map(
      pages.flatMap(({ items }) => items).map((m) => [m.id, m]),
    ).values(),
  ]
}
