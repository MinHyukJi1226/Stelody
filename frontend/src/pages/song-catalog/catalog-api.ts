import { infiniteQueryOptions } from '@tanstack/react-query'
import { getSongs } from '@/features/songs/api'
import { songCatalogOptions } from '@/features/songs/queries'
import { apiClient } from '@/shared/api/client'
import type { ApiPaths, ApiSchemas, SongPage } from '@/shared/api/types'

type GroupQuery = NonNullable<
  ApiPaths['/api/v1/songs/groups']['get']['parameters']['query']
>
export type CatalogGrouping = GroupQuery['groupBy']
export type CatalogSort = NonNullable<GroupQuery['sort']>
export type CatalogGroup =
  ApiSchemas['com.stelody.song.service.SongGroupService.Group']
type GroupPage = ApiSchemas['com.stelody.song.service.SongGroupService.Page']

export const groupKey = (groupBy: CatalogGrouping, sort: CatalogSort) =>
  ['catalog-groups', groupBy, sort] as const
export const groupSongsKey = (
  groupBy: CatalogGrouping,
  sort: CatalogSort,
  id?: string,
) =>
  id
    ? (['catalog-group-songs', groupBy, sort, id] as const)
    : (['catalog-group-songs', groupBy, sort] as const)
const nextCursor = (
  last: { hasNext: boolean; nextCursor: string | null },
  cursors: (string | null)[],
) =>
  last.hasNext && last.nextCursor && !cursors.includes(last.nextCursor)
    ? last.nextCursor
    : undefined

export function catalogOptions(sort: CatalogSort) {
  if (sort === 'LATEST') return songCatalogOptions
  return infiniteQueryOptions({
    queryKey: ['songs', 'catalog', sort],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) =>
      getSongs(
        {
          mode: 'BROWSE',
          sort,
          size: 20,
          ...(pageParam ? { cursor: pageParam } : {}),
        },
        signal,
      ),
    getNextPageParam: (last, _pages, _param, cursors) =>
      nextCursor(last, cursors),
  })
}

export const catalogGroupsOptions = (
  groupBy: CatalogGrouping,
  sort: CatalogSort,
) =>
  infiniteQueryOptions({
    queryKey: groupKey(groupBy, sort),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({
        groupBy,
        sort,
        size: '10',
        songSize: '18',
      })
      if (pageParam) params.set('cursor', pageParam)
      return apiClient.request<GroupPage>(`/songs/groups?${params}`, { signal })
    },
    getNextPageParam: (last, _pages, _param, cursors) =>
      nextCursor(last, cursors),
  })

export const catalogGroupSongsOptions = (
  groupBy: CatalogGrouping,
  sort: CatalogSort,
  group: CatalogGroup,
) =>
  infiniteQueryOptions({
    queryKey: groupSongsKey(groupBy, sort, group.id),
    initialPageParam: null as string | null,
    initialData: { pages: [group.songs], pageParams: [null as string | null] },
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ groupBy, sort, size: '20' })
      if (pageParam) params.set('cursor', pageParam)
      return apiClient.request<SongPage>(
        `/songs/groups/${encodeURIComponent(group.id)}/songs?${params}`,
        { signal },
      )
    },
    getNextPageParam: (last, _pages, _param, cursors) =>
      nextCursor(last, cursors),
  })
