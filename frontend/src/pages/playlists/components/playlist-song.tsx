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
  const origin = song.work?.artists.map(({ name }) => name).join(' · ')
  return (
    <article className="rounded-2xl bg-surface p-4">
      <Link
        to={`/songs/${song.id}`}
        className="flex min-w-0 items-center gap-3 rounded-lg"
        aria-label={`${song.title} 상세 보기`}
      >
        <Artwork
          src={song.thumbnailUrl}
          className="h-[54px] w-24 rounded-lg md:h-[72px] md:w-32"
        />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h2 className="break-words text-lg font-bold leading-[1.5]">
            {song.title}
          </h2>
          {song.type === 'COVER' && (
            <p className="break-words text-xs leading-[1.5] text-muted">
              원곡 · {origin || song.work?.title || '정보 준비 중'}
            </p>
          )}
          <p className="break-words text-sm leading-[1.5] text-muted">
            {participantLabel(song, members)}
          </p>
        </div>
      </Link>
    </article>
  )
}
