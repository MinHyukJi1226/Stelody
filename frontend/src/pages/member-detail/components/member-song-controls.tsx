import type { MemberSongFilters } from '@/features/members/song-filters'
import { Button } from '@/shared/ui/button'
import { TabsList, TabsTrigger } from '@/shared/ui/tabs'

export function MemberSongControls({
  filters,
  onChange,
}: {
  filters: MemberSongFilters
  onChange: (value: Partial<MemberSongFilters>) => void
}) {
  return (
    <>
      <TabsList aria-label="참여곡 유형">
        {(
          [
            ['ALL', '전체'],
            ['ORIGINAL', '오리지널'],
            ['COVER', '커버'],
          ] as const
        ).map(([value, label]) => (
          <TabsTrigger key={value} value={value}>
            {label}
          </TabsTrigger>
        ))}
      </TabsList>
      <div className="flex flex-wrap gap-2">
        <Button
          aria-pressed={filters.collaboration}
          onClick={() => onChange({ collaboration: !filters.collaboration })}
          className={
            filters.collaboration ? 'bg-brand-fill text-on-accent' : ''
          }
        >
          공동 참여곡만
        </Button>
        <label className="sr-only" htmlFor="member-song-sort">
          참여곡 정렬
        </label>
        <select
          id="member-song-sort"
          value={filters.sort}
          onChange={(e) =>
            onChange({ sort: e.target.value as MemberSongFilters['sort'] })
          }
          className="min-h-[53px] rounded-xl bg-surface p-4 text-sm text-ink"
        >
          <option value="LATEST">최신순</option>
          <option value="VIEWS">조회수순</option>
        </select>
      </div>
    </>
  )
}
