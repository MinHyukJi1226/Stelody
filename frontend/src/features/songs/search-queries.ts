import { infiniteQueryOptions } from '@tanstack/react-query'
import { getSongs } from './api'
import { hasSearchConditions, type SearchCriteria } from './search-params'

export function songSearchOptions(criteria: SearchCriteria) {
  return infiniteQueryOptions({
    enabled: hasSearchConditions(criteria),
    queryKey: ['songs', 'search', criteria],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) =>
      getSongs(
        {
          ...(criteria.q.trim()
            ? { q: criteria.q.trim(), mode: 'SEARCH' as const }
            : { mode: 'BROWSE' as const }),
          type: criteria.type,
          memberIds: criteria.memberIds,
          sort: criteria.sort,
          size: 20,
          ...(criteria.startDate ? { startDate: criteria.startDate } : {}),
          ...(criteria.endDate ? { endDate: criteria.endDate } : {}),
          ...(pageParam ? { cursor: pageParam } : {}),
        },
        signal,
      ),
    getNextPageParam: (last, _pages, _lastParam, pageParams) =>
      last.hasNext && last.nextCursor && !pageParams.includes(last.nextCursor)
        ? last.nextCursor
        : undefined,
  })
}
