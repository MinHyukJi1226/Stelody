import { useQuery } from '@tanstack/react-query'
import { Button } from '@/shared/ui/button'
import { cn } from '@/shared/lib/utils'
import { favoriteErrorMessage, favoriteStateOptions } from './queries'
import { useFavoriteChange } from './use-favorite-change'

export function FavoriteButton({
  songId,
  songTitle,
  mode = 'toggle',
  knownFavorited,
  className,
}: {
  songId: string
  songTitle: string
  mode?: 'toggle' | 'remove'
  knownFavorited?: boolean
  className?: string
}) {
  const action = useFavoriteChange(songId)
  const { session, userId } = action
  const state = useQuery({
    ...favoriteStateOptions(userId, songId),
    enabled: !!userId && !session.isError && !action.pending,
    initialData: () =>
      userId && knownFavorited !== undefined
        ? { songId, favorited: knownFavorited }
        : undefined,
  })
  const saved = state.data?.favorited ?? false
  const checking =
    session.isPending || session.isFetching || (!!userId && state.isPending)
  const message = action.error
    ? favoriteErrorMessage(action.error)
    : state.isError && userId
      ? '즐겨찾기 상태를 확인하지 못했어요. 다시 확인해 주세요.'
      : session.isError
        ? '로그인 상태를 확인하지 못했어요. 다시 확인해 주세요.'
        : null
  const click = () => {
    if (session.isError) {
      void session.refetch()
      return
    }
    if (!userId) {
      action.requireLogin(true)
      return
    }
    if (state.isError) {
      void state.refetch()
      return
    }
    action.change(mode === 'remove' ? false : !saved)
  }
  return (
    <div
      className={cn(
        'flex min-w-0 flex-col items-start gap-1',
        mode === 'toggle' && 'min-h-[72px] w-44',
        className,
      )}
    >
      <Button
        aria-label={
          checking
            ? `${songTitle} 즐겨찾기 확인 중`
            : `${songTitle} 즐겨찾기 ${mode === 'remove' || saved ? '해제' : '저장'}`
        }
        {...(mode === 'toggle' ? { 'aria-pressed': saved } : {})}
        aria-busy={action.pending}
        disabled={checking || action.pending}
        onClick={click}
        className={cn(
          mode === 'toggle' &&
            'h-12 min-h-12 w-full border border-line p-3 font-bold leading-5',
        )}
      >
        {mode === 'toggle' && (
          <span
            aria-hidden="true"
            className={cn('text-xl font-normal', saved && 'text-error')}
          >
            {saved ? '♥' : '♡'}
          </span>
        )}
        {checking
          ? '확인 중…'
          : action.pending
            ? '변경 중…'
            : state.isError || session.isError
              ? '다시 확인'
              : mode === 'remove'
                ? '즐겨찾기 해제'
                : '즐겨찾기'}
      </Button>
      {message ? (
        <p role="status" className="text-xs leading-[1.5] text-error">
          {message}
        </p>
      ) : (
        mode === 'toggle' &&
        saved && (
          <p className="text-[11px] leading-[18px] text-muted">
            즐겨찾기에 추가했어요.
          </p>
        )
      )}
    </div>
  )
}
