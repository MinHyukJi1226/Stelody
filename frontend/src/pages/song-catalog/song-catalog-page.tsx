import { useQueryClient } from '@tanstack/react-query'
import { catalogOptions, groupKey, groupSongsKey } from './catalog-api'
import { useSearchParams } from 'react-router'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { FlatCatalog } from './catalog-list'
import { GroupedCatalog } from './catalog-groups'
import { CatalogSortMenu } from './catalog-sort'
import type { CatalogGrouping, CatalogSort } from './catalog-api'

const groups: { value: CatalogGrouping; label: string }[] = [
  { value: 'TYPE', label: '오리지널 / 커버' },
  { value: 'MEMBER', label: '멤버별' },
  { value: 'ORIGINAL_ARTIST', label: '원곡 가수별' },
]
export function SongCatalogPage() {
  const client = useQueryClient()
  const [params, setParams] = useSearchParams()
  const grouping = params.get('groupBy')
  const sorting = params.get('sort') ?? 'LATEST'
  const validGrouping =
    grouping === null || groups.some((item) => item.value === grouping)
  const validSort = ['LATEST', 'OLDEST', 'VIEWS'].includes(sorting)
  const groupBy = grouping as CatalogGrouping | null
  const sort = sorting as CatalogSort
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
    if (nextGroup) next.set('groupBy', nextGroup)
    if (nextSort !== 'LATEST') next.set('sort', nextSort)
    setParams(next, { preventScrollReset: true })
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
            onRetry={() => setParams({})}
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
      <footer className="grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
