import { useInfiniteQuery } from '@tanstack/react-query'
import { Artwork } from '@/shared/ui/artwork'
import { cn } from '@/shared/lib/utils'
import type { Playlist } from './api'
import { playlistItemsOptions } from './queries'

export function PlaylistCover({
  playlist,
  userId,
  className,
}: {
  playlist: Playlist
  userId: string
  className?: string
}) {
  const query = useInfiniteQuery({
    ...playlistItemsOptions(userId, playlist.id),
    enabled: !!userId && playlist.availableCount > 0,
  })
  const thumbnails =
    query.data?.pages[0]?.items
      .flatMap((item) =>
        item.available && item.song?.thumbnailUrl
          ? [item.song.thumbnailUrl]
          : [],
      )
      .slice(0, 4) ?? []

  return (
    <div
      className={cn(
        'grid aspect-square grid-cols-2 grid-rows-2 overflow-hidden rounded-lg bg-line',
        className,
      )}
    >
      {Array.from({ length: 4 }, (_, index) => (
        <Artwork
          key={index}
          src={thumbnails[index] ?? null}
          className="size-full min-h-0 min-w-0"
          fallbackText=""
        />
      ))}
    </div>
  )
}
