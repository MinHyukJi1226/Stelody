import { infiniteQueryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import type { ApiSchemas } from '@/shared/api/types'

type MemberPage = ApiSchemas['com.stelody.member.dto.MemberDtos.Page']

export const homeMembersOptions = infiniteQueryOptions({
  queryKey: ['members', 'home'],
  initialPageParam: null as string | null,
  queryFn: ({ pageParam, signal }) => {
    const params = new URLSearchParams({ status: 'ALL', size: '50' })
    if (pageParam) params.set('cursor', pageParam)
    return apiClient.request<MemberPage>(`/members?${params}`, { signal })
  },
  getNextPageParam: (last, _pages, _lastParam, pageParams) =>
    last.hasNext && last.nextCursor && !pageParams.includes(last.nextCursor)
      ? last.nextCursor
      : undefined,
})
