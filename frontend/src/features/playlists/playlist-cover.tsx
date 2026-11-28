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
  const collage = playlist.totalCount >= 4
  const firstItem = query.data?.pages[0]?.items[0]
  const firstThumbnail =
    playlist.totalCount > 0 && firstItem?.available
      ? (firstItem.song?.thumbnailUrl ?? null)
      : null
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
        'grid aspect-square overflow-hidden rounded-lg bg-line',
        collage ? 'grid-cols-2 grid-rows-2' : 'grid-cols-1 grid-rows-1',
        className,
      )}
    >
      {Array.from({ length: collage ? 4 : 1 }, (_, index) => (
        <Artwork
          key={index}
          src={collage ? (thumbnails[index] ?? null) : firstThumbnail}
          className="size-full min-h-0 min-w-0"
          fallbackText={collage ? '' : '♪'}
        />
      ))}
    </div>
  )
}
