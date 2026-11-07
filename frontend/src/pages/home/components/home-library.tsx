import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { favoriteListOptions } from '@/features/favorites/queries'
import type { Playlist } from '@/features/playlists/api'
import {
  playlistItemsOptions,
  playlistListOptions,
} from '@/features/playlists/queries'
import { Artwork } from '@/shared/ui/artwork'
import { StatePanel } from '@/shared/ui/state-panel'
import { HomeSection } from './home-section'
import { HomeSongGrid, HomeSongSkeleton, homeGrid } from './home-song-grid'
import { HomeCarousel } from './home-song-carousel'

function PlaylistPreview({
  playlist,
  userId,
}: {
  playlist: Playlist
  userId: string
}) {
  const query = useInfiniteQuery({
    ...playlistItemsOptions(userId, playlist.id),
    enabled: playlist.availableCount > 0,
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
    <Link
      to={`/library/playlists/${playlist.id}`}
      className="grid min-w-0 gap-2 rounded-lg"
    >
      <div className="grid aspect-square grid-cols-2 grid-rows-2 overflow-hidden rounded-lg bg-line">
        {Array.from({ length: 4 }, (_, index) => (
          <Artwork
            key={index}
            src={thumbnails[index] ?? null}
            className="size-full min-h-0 min-w-0"
            fallbackText=""
          />
        ))}
      </div>
      <span className="truncate text-sm" title={playlist.name}>
        {playlist.name}
      </span>
      <span className="text-xs text-muted">
        {playlist.totalCount.toLocaleString('ko-KR')}곡
      </span>
    </Link>
  )
}

export function HomeLibrary({ userId }: { userId: string }) {
  const playlists = useInfiniteQuery(playlistListOptions(userId))
  const favorites = useInfiniteQuery(favoriteListOptions(userId))
  const lists = playlists.data?.pages.flatMap((page) => page.items) ?? []
  const songs =
    favorites.data?.pages.flatMap((page) =>
      page.items.flatMap((item) =>
        item.available && item.song ? [item.song] : [],
      ),
    ) ?? []

  return (
    <>
      {(playlists.isPending || playlists.isError || lists.length > 0) && (
        <HomeSection title="나의 플레이리스트" loading={playlists.isPending}>
          {playlists.isPending ? (
            <div
              role="status"
              aria-label="플레이리스트 불러오는 중"
              className={homeGrid}
            >
              <div className="aspect-square rounded-lg bg-line" />
            </div>
          ) : playlists.isError && !playlists.data ? (
            <StatePanel
              title="플레이리스트를 불러오지 못했습니다"
              onRetry={() => void playlists.refetch()}
            />
          ) : (
            <>
              <HomeCarousel
                label="나의 플레이리스트"
                square
                hasMore={playlists.hasNextPage}
                lastPageFull={lists.length % 6 === 0}
                loadingMore={playlists.isFetchingNextPage}
                onLoadMore={async () =>
                  !(await playlists.fetchNextPage()).isError
                }
                pages={Array.from(
                  { length: Math.ceil(lists.length / 6) },
                  (_, index) => (
                    <div key={index} className={homeGrid}>
                      {lists
                        .slice(index * 6, (index + 1) * 6)
                        .map((playlist) => (
                          <PlaylistPreview
                            key={playlist.id}
                            playlist={playlist}
                            userId={userId}
                          />
                        ))}
                    </div>
                  ),
                )}
              />
              {playlists.isError && (
                <StatePanel
                  title="플레이리스트를 더 불러오지 못했습니다"
                  onRetry={() => void playlists.fetchNextPage()}
                />
              )}
            </>
          )}
        </HomeSection>
      )}
      {(favorites.isPending || favorites.isError || songs.length > 0) && (
        <HomeSection title="내가 좋아하는 곡" loading={favorites.isPending}>
          {favorites.isPending ? (
            <HomeSongSkeleton />
          ) : favorites.isError && !favorites.data ? (
            <StatePanel
              title="좋아하는 곡을 불러오지 못했습니다"
              onRetry={() => void favorites.refetch()}
            />
          ) : (
            <>
              <HomeCarousel
                label="내가 좋아하는 곡"
                hasMore={
                  favorites.hasNextPage &&
                  (favorites.data?.pages[0]?.availableCount ?? songs.length) > 6
                }
                lastPageFull={songs.length % 6 === 0}
                loadingMore={favorites.isFetchingNextPage}
                onLoadMore={async () =>
                  !(await favorites.fetchNextPage()).isError
                }
                pages={Array.from(
                  { length: Math.ceil(songs.length / 6) },
                  (_, index) => (
                    <HomeSongGrid
                      key={index}
                      songs={songs.slice(index * 6, (index + 1) * 6)}
                    />
                  ),
                )}
              />
              {favorites.isError && (
                <StatePanel
                  title="좋아하는 곡을 더 불러오지 못했습니다"
                  onRetry={() => void favorites.fetchNextPage()}
                />
              )}
            </>
          )}
        </HomeSection>
      )}
    </>
  )
}
