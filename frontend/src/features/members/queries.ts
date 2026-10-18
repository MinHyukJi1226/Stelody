import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import type { ApiSchemas, SongPage } from '@/shared/api/types'
import type { MemberSongFilters } from './song-filters'

type MemberPage = ApiSchemas['com.stelody.member.dto.MemberDtos.Page']
export type MemberDetail =
  ApiSchemas['com.stelody.member.dto.MemberDtos.Detail']

export const memberDetailOptions = (id: string) =>
  queryOptions({
    queryKey: ['members', 'detail', id],
    queryFn: ({ signal }) =>
      apiClient.request<MemberDetail>(`/members/${encodeURIComponent(id)}`, {
        signal,
      }),
  })

export const memberSongsOptions = (id: string, filters: MemberSongFilters) =>
  infiniteQueryOptions({
    queryKey: ['members', 'detail', id, 'songs', filters],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({
        type: filters.type,
        collaboration: String(filters.collaboration),
        sort: filters.sort,
        size: '20',
      })
      if (pageParam) params.set('cursor', pageParam)
      return apiClient.request<SongPage>(
        `/members/${encodeURIComponent(id)}/songs?${params}`,
        { signal },
      )
    },
    getNextPageParam: (last, _pages, _lastParam, pageParams) =>
      last.hasNext && last.nextCursor && !pageParams.includes(last.nextCursor)
        ? last.nextCursor
        : undefined,
  })

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
