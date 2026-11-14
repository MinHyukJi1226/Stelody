import { Link } from 'react-router'
import type { FavoriteItem as Item } from '@/features/favorites/api'
import { useFavoriteChange } from '@/features/favorites/use-favorite-change'
import { participantLabel } from '@/features/songs/participant-label'
import type { MemberCard } from '@/shared/api/types'
import { Artwork } from '@/shared/ui/artwork'
import { LibraryDeleteButton } from './library-delete-button'

export function FavoriteItem({
  item,
  members,
}: {
  item: Item
  members: readonly MemberCard[] | undefined
}) {
  const action = useFavoriteChange(item.songId)
  const song = item.available ? item.song : null
  const artwork = (
    <Artwork
      src={song?.thumbnailUrl ?? null}
      className="aspect-video w-24 rounded-2xl md:w-[200px]"
      fallbackText=""
    />
  )

  return (
    <article className="flex min-w-0 items-center justify-between gap-3 md:gap-4">
      {song ? (
        <Link
          to={`/songs/${song.id}`}
          aria-label={`${song.title} 상세 보기`}
          className="flex min-w-0 flex-1 items-center gap-3 rounded-2xl md:gap-4"
        >
          {artwork}
          <div className="grid min-w-0 gap-1">
            <h2 className="break-words text-sm font-medium md:text-base">
              {song.title}
            </h2>
            <p className="break-words text-xs md:text-sm">
              {participantLabel(song, members)}
            </p>
          </div>
        </Link>
      ) : (
        <div className="flex min-w-0 flex-1 items-center gap-3 md:gap-4">
          {artwork}
          <div className="grid min-w-0 gap-1">
            <h2 className="break-words text-sm font-medium md:text-base">
              현재 이용할 수 없는 곡
            </h2>
            <p className="break-words text-xs text-muted md:text-sm">
              {item.unavailableMessage || '저장한 항목은 유지됩니다.'}
            </p>
          </div>
        </div>
      )}
      <LibraryDeleteButton
        label={`${song?.title ?? '이용 불가 곡'} 즐겨찾기 해제`}
        pending={action.pending || !action.userId || action.session.isError}
        onConfirm={() => action.change(false)}
      />
    </article>
  )
}
