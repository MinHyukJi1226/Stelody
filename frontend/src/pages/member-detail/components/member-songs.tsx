import { useSearchParams } from 'react-router'
import {
  memberSongParams,
  readMemberSongFilters,
} from '@/features/members/song-filters'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { MemberSongSection } from './member-song-section'

export function MemberSongs({ memberId }: { memberId: string }) {
  const [params, setParams] = useSearchParams()
  const parsed = readMemberSongFilters(params)
  if (!parsed.success)
    return (
      <StatePanel
        title="곡 조회 조건이 올바르지 않습니다"
        description="필터를 다시 선택하거나 초기화해 주세요."
        onRetry={() =>
          setParams(new URLSearchParams(), { preventScrollReset: true })
        }
      />
    )
  const filters = parsed.data
  const participation =
    filters.participation ?? (filters.multipleParticipants ? 'MULTIPLE' : 'ALL')
  const base = {
    ...filters,
    participation: 'ALL' as const,
    multipleParticipants: false,
  }
  const coverLabel =
    participation === 'SOLO'
      ? '개인'
      : participation === 'MULTIPLE'
        ? '듀엣 및 단체'
        : '전체'
  return (
    <div className="flex min-w-0 flex-col gap-8">
      <MemberSongSection
        memberId={memberId}
        label="전체"
        filters={{ ...base, type: 'ALL' }}
      />
      <MemberSongSection
        memberId={memberId}
        label="오리지널"
        filters={{ ...base, type: 'ORIGINAL' }}
      />
      <MemberSongSection
        key={participation}
        memberId={memberId}
        label={`커버 ${coverLabel}`}
        filters={{ ...filters, type: 'COVER', participation }}
        controls={
          <div role="group" aria-label="커버 참여 인원" className="flex gap-2">
            {(
              [
                ['SOLO', '개인'],
                ['MULTIPLE', '듀엣 및 단체'],
              ] as const
            ).map(([value, label]) => (
              <Button
                key={value}
                aria-pressed={participation === value}
                variant={participation === value ? 'primary' : 'secondary'}
                className={`min-h-10 rounded-lg px-3 py-2 text-xs ${participation === value ? '' : 'bg-subtle text-on-accent'}`}
                onClick={() => {
                  const next = participation === value ? 'ALL' : value
                  setParams(
                    memberSongParams({
                      ...filters,
                      type: 'COVER',
                      participation: next,
                      multipleParticipants: next === 'MULTIPLE',
                    }),
                    { preventScrollReset: true },
                  )
                }}
              >
                {label}
              </Button>
            ))}
          </div>
        }
      />
    </div>
  )
}
