import { FavoriteButton } from '@/features/favorites/favorite-button'
import type { FavoriteItem as Item } from '@/features/favorites/api'
import { SongCard } from '@/features/songs/song-card'
import type { MemberCard } from '@/shared/api/types'

export function FavoriteItem({
  item,
  members,
}: {
  item: Item
  members: readonly MemberCard[] | undefined
}) {
  const remove = (
    <FavoriteButton
      songId={item.songId}
      songTitle={item.song?.title ?? '이용 불가 곡'}
      mode="remove"
      knownFavorited
    />
  )
  if (item.available && item.song)
    return (
      <SongCard
        song={item.song}
        members={members}
        variant="library"
        actions={remove}
      />
    )
  return (
    <article className="flex min-w-0 flex-col items-start gap-4 rounded-2xl bg-surface p-6 text-muted">
      <h3 className="text-lg font-bold leading-[1.5]">
        현재 이용할 수 없는 곡
      </h3>
      <p className="text-sm leading-[1.5]">
        {item.unavailableMessage || '저장한 항목은 유지됩니다.'}
      </p>
      {remove}
    </article>
  )
}
