import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { catalogOptions, groupKey, groupSongsKey } from './catalog-api'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { FlatCatalog } from './catalog-list'
import { GroupedCatalog } from './catalog-groups'
import { CatalogSortMenu } from './catalog-sort'
import type { CatalogGrouping, CatalogSort } from './catalog-api'
import {
  isCatalogGrouping,
  readCatalogGrouping,
  rememberCatalogGrouping,
} from './catalog-grouping'

const groups: { value: CatalogGrouping; label: string }[] = [
  { value: 'TYPE', label: '오리지널 / 커버' },
  { value: 'MEMBER', label: '멤버별' },
  { value: 'ORIGINAL_ARTIST', label: '원곡 가수별' },
]
const emptyHistory: Record<string, unknown> = {}
export function SongCatalogPage() {
  const client = useQueryClient()
  const location = useLocation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const history =
    location.state && typeof location.state === 'object'
      ? (location.state as Record<string, unknown>)
      : emptyHistory
  const hasHistoryGrouping = Object.hasOwn(history, 'catalogGrouping')
  const historyGrouping = history.catalogGrouping
  const grouping = params.has('groupBy')
    ? params.get('groupBy')
    : hasHistoryGrouping &&
        (historyGrouping === null || typeof historyGrouping === 'string')
      ? historyGrouping
      : readCatalogGrouping()
  const sorting = params.get('sort') ?? 'LATEST'
  const validGrouping = grouping === null || isCatalogGrouping(grouping)
  const validSort = ['LATEST', 'OLDEST', 'VIEWS'].includes(sorting)
  const groupBy = grouping as CatalogGrouping | null
  const sort = sorting as CatalogSort
  useEffect(() => {
    if (validGrouping) rememberCatalogGrouping(groupBy)
    if (!params.has('groupBy') && hasHistoryGrouping) return
    const clean = new URLSearchParams(params)
    clean.delete('groupBy')
    void navigate(
      {
        pathname: location.pathname,
        search: clean.toString(),
        hash: location.hash,
      },
      {
        replace: true,
        preventScrollReset: true,
        state: { ...history, catalogGrouping: grouping },
      },
    )
  }, [
    groupBy,
    grouping,
    validGrouping,
    params,
    hasHistoryGrouping,
    history,
    navigate,
    location.pathname,
    location.hash,
  ])
  function change(nextGroup: CatalogGrouping | null, nextSort: CatalogSort) {
    if (nextGroup === groupBy && nextSort === sort) return
    if (nextGroup) {
      client.removeQueries({
        queryKey: groupKey(nextGroup, nextSort),
        exact: true,
      })
      client.removeQueries({ queryKey: groupSongsKey(nextGroup, nextSort) })
    } else {
      client.removeQueries({
        queryKey: catalogOptions(nextSort).queryKey,
        exact: true,
      })
    }
    const next = new URLSearchParams()
    if (nextSort !== 'LATEST') next.set('sort', nextSort)
    void navigate(
      {
        pathname: location.pathname,
        search: next.toString(),
        hash: location.hash,
      },
      {
        preventScrollReset: true,
        state: { ...history, catalogGrouping: nextGroup },
      },
    )
  }
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <section
        aria-labelledby="catalog-title"
        className="flex min-w-0 flex-1 flex-col gap-8 px-5 py-8 md:px-8"
      >
        <div className="home-section-enter flex flex-col gap-3">
          <div className="flex items-center justify-between gap-4">
            <h1 id="catalog-title" className="text-2xl font-bold leading-[1.5]">
              전체곡
            </h1>
            <CatalogSortMenu
              sort={validSort ? sort : 'LATEST'}
              onChange={(value) =>
                change(validGrouping ? groupBy : null, value)
              }
            />
          </div>
          <div
            role="group"
            aria-label="곡 분류"
            className="flex flex-wrap gap-2"
          >
            {groups.map(({ value, label }) => (
              <Button
                key={value}
                aria-pressed={groupBy === value}
                variant={groupBy === value ? 'primary' : 'secondary'}
                className={`min-h-10 rounded-lg px-3 py-2 text-xs ${groupBy !== value ? 'bg-subtle text-on-accent' : ''}`}
                onClick={() =>
                  change(
                    groupBy === value ? null : value,
                    validSort ? sort : 'LATEST',
                  )
                }
              >
                {label}
              </Button>
            ))}
          </div>
        </div>
        {!validGrouping || !validSort ? (
          <StatePanel
            title="조회 조건을 확인해 주세요"
            description="분류 또는 정렬 조건이 올바르지 않습니다."
            onRetry={() => {
              rememberCatalogGrouping(null)
              void navigate(
                { pathname: location.pathname, hash: location.hash },
                { state: { ...history, catalogGrouping: null } },
              )
            }}
          />
        ) : groupBy ? (
          <GroupedCatalog
            key={`${groupBy}-${sort}`}
            groupBy={groupBy}
            sort={sort}
          />
        ) : (
          <FlatCatalog key={sort} sort={sort} />
        )}
      </section>
    </div>
  )
}
