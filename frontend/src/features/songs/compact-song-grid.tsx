import { cn } from '@/shared/lib/utils'
import { Link } from 'react-router'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import type { Song } from '@/features/songs/api'
import { participantLabel } from '@/features/songs/participant-label'
import { Artwork } from '@/shared/ui/artwork'
import { HomeScrollingText } from '@/pages/home/components/home-scrolling-text'

export const compactSongGrid =
  'grid grid-cols-2 gap-x-4 gap-y-6 md:grid-cols-[repeat(auto-fill,200px)] md:gap-x-6'

export function CompactSongGrid({
  songs,
  className,
}: {
  songs: readonly Song[]
  className?: string
}) {
  const members = useMemberDirectory(
    songs.some((song) => song.participants.length >= 2),
  )
  return (
    <div className={cn(compactSongGrid, className)}>
      {songs.map((song) => (
        <article key={song.id} className="min-w-0">
          <Link
            to={`/songs/${song.id}`}
            aria-label={`${song.title} 상세 보기`}
            className="grid gap-2 rounded-lg"
          >
            <div className="relative">
              <Artwork
                src={song.thumbnailUrl}
                className="aspect-video w-full rounded-lg"
              />
              <span
                className={`absolute top-2 right-2 rounded-lg px-3 py-1 text-xs ${song.type === 'ORIGINAL' ? 'bg-tint text-brand' : 'bg-surface text-ink'}`}
              >
                {song.type === 'ORIGINAL' ? '오리지널' : '커버'}
              </span>
            </div>
            <h3 className="min-w-0 text-sm" title={song.title}>
              <HomeScrollingText>{song.title}</HomeScrollingText>
            </h3>
            <p className="min-w-0 text-xs text-muted">
              <HomeScrollingText>
                {participantLabel(song, members)}
              </HomeScrollingText>
            </p>
          </Link>
        </article>
      ))}
    </div>
  )
}

export function CompactSongSkeleton({
  count = 3,
  className,
}: {
  count?: number
  className?: string
}) {
  return (
    <div
      role="status"
      aria-label="노래 불러오는 중"
      className={cn(compactSongGrid, className)}
    >
      {Array.from({ length: count }, (_, index) => (
        <div key={index} aria-hidden="true" className="grid gap-2">
          <div className="aspect-video rounded-lg bg-line" />
          <div className="h-[21px] w-3/4 rounded bg-line" />
          <div className="h-[18px] w-1/2 rounded bg-line" />
        </div>
      ))}
    </div>
  )
}
