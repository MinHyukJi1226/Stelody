import type { ReactNode } from 'react'
import type { SongDetail } from '@/features/songs/detail-queries'
import type { MemberCard } from '@/shared/api/types'
import { participantLabel } from '@/features/songs/participant-label'
import { Artwork } from '@/shared/ui/artwork'

export function SongHeading({
  detail,
  actions,
  members,
}: {
  detail: SongDetail
  actions: ReactNode
  members: readonly MemberCard[] | undefined
}) {
  const { song } = detail

  return (
    <header className="home-section-enter relative isolate flex min-h-32 min-w-0 flex-wrap items-end justify-between gap-3 overflow-hidden bg-bg px-5 py-5 md:min-h-[clamp(150px,15vw,280px)] lg:min-h-[clamp(150px,18.5vw,344px)] md:px-10 md:py-8">
      {song.thumbnailUrl && (
        <Artwork
          src={song.thumbnailUrl}
          fallbackText=""
          className="absolute inset-0 -z-10 size-full opacity-30"
        />
      )}
      <div className="flex min-w-0 flex-1 flex-col items-start gap-2">
        <span className="rounded-lg bg-tint px-2 py-1 text-xs text-brand">
          {song.type === 'COVER' ? '커버' : '오리지널'}
        </span>
        <h1 className="text-2xl font-bold wrap-anywhere md:text-4xl">
          {song.title}
        </h1>
        <p className="text-sm wrap-anywhere">
          {participantLabel(song, members)}
        </p>
      </div>
      {actions}
    </header>
  )
}
