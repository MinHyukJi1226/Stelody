import { Link } from 'react-router'
import type { MemberCard } from '@/shared/api/types'
import type { Song } from '@/features/songs/api'
import { participantLabel } from '@/features/songs/participant-label'
import { Artwork } from '@/shared/ui/artwork'

export function PlaylistSong({
  song,
  members,
}: {
  song: Song
  members: readonly MemberCard[] | undefined
}) {
  return (
    <Link
      to={`/songs/${song.id}`}
      className="flex min-w-0 flex-1 items-center gap-3 rounded-lg md:gap-4"
      aria-label={`${song.title} 상세 보기`}
    >
      <Artwork
        src={song.thumbnailUrl}
        className="aspect-video w-20 rounded-xl sm:w-32 md:w-[200px]"
      />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <h2 className="wrap-anywhere text-sm font-medium md:text-base leading-[1.5]">
          {song.title}
        </h2>
        <p className="wrap-anywhere text-xs leading-[1.5] text-muted md:text-sm">
          {participantLabel(song, members)}
        </p>
      </div>
    </Link>
  )
}
