import { useId, useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { StatePanel } from '@/shared/ui/state-panel'
import { useToast } from '@/shared/ui/toast-context'
import { ApiError } from '@/shared/api/errors'
import { PlaylistNameForm } from './playlist-name-form'
import {
  playlistErrorMessage,
  playlistKeys,
  playlistListOptions,
} from './queries'
import { usePlaylistWrite } from './use-playlist-write'
import type { Playlist } from './api'

export function PlaylistPicker({
  userId,
  songId,
  returnTo,
  onClose,
}: {
  userId: string
  songId: string
  returnTo: string
  onClose: () => void
}) {
  const [creating, setCreating] = useState(false)
  const [formBusy, setFormBusy] = useState(false)
  const [error, setError] = useState<string>()
  const client = useQueryClient()
  const query = useInfiniteQuery({
    ...playlistListOptions(userId, songId),
    staleTime: 0,
  })
  const action = usePlaylistWrite(userId, returnTo)
  const notify = useToast()
  const titleId = useId()
  const items = [
    ...new Map(
      query.data?.pages.flatMap((p) => p.items).map((p) => [p.id, p]) ?? [],
    ).values(),
  ]
  const busy = action.pending || formBusy
  const denied =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  const retry = () =>
    query.error instanceof ApiError && query.error.status === 400
      ? client.resetQueries({
          queryKey: playlistKeys.list(userId, songId),
          exact: true,
        })
      : query.isFetchNextPageError
        ? query.fetchNextPage()
        : query.refetch()
  async function add(playlist: Playlist) {
    setError(undefined)
    try {
      const saved = await action.write({ kind: 'add', playlist, songId })
      if (saved) {
        notify('플레이리스트에 추가했어요.')
        onClose()
      }
    } catch (failure) {
      setError(playlistErrorMessage(failure))
    }
  }
  return (
    <Dialog
      labelledBy={titleId}
      busy={busy}
      onDismiss={onClose}
      className={
        creating
          ? ''
          : 'h-[min(505px,calc(100dvh-40px))] max-w-[492px] overflow-hidden'
      }
    >
      <div
        className={
          creating
            ? 'flex flex-col gap-4'
            : 'flex h-full min-h-0 flex-col gap-4'
        }
      >
        <div className="flex w-full shrink-0 items-center gap-3">
          <h2
            id={titleId}
            className={`min-w-0 flex-1 break-words font-bold leading-[1.5] ${creating ? 'text-2xl' : 'text-xl md:text-2xl'}`}
          >
            {creating ? '새 플레이리스트' : '플레이리스트 추가'}
          </h2>
          {!creating && (
            <Button
              size="icon"
              className="border-0 bg-transparent"
              aria-label="닫기"
              disabled={busy}
              onClick={onClose}
            >
              <svg
                aria-hidden="true"
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              >
                <path d="m6 6 12 12M18 6 6 18" />
              </svg>
            </Button>
          )}
        </div>
        {creating ? (
          <PlaylistNameForm
            userId={userId}
            songId={songId}
            returnTo={returnTo}
            modal
            busyChanged={setFormBusy}
            onCancel={() => setCreating(false)}
            onDone={onClose}
          />
        ) : (
          <>
            <div className="flex min-h-0 flex-1 flex-col items-start gap-3 overflow-y-auto">
              {error && (
                <p role="alert" className="text-sm text-error">
                  {error}
                </p>
              )}
              {query.isPending ? (
                <p role="status">플레이리스트 불러오는 중…</p>
              ) : denied ? (
                <StatePanel title="이 목록에 접근할 수 없습니다" />
              ) : query.isError && !items.length ? (
                <StatePanel
                  title="플레이리스트를 불러오지 못했어요"
                  onRetry={() => void retry()}
                />
              ) : (
                <>
                  {!items.length && (
                    <p className="text-sm leading-[1.5] text-muted">
                      아직 만든 플레이리스트가 없습니다.
                    </p>
                  )}
                  {items.map((playlist) => (
                    <div
                      key={playlist.id}
                      className="flex w-full shrink-0 items-center justify-between gap-3 py-2"
                    >
                      <h3 className="min-w-0 leading-6 wrap-anywhere">
                        {playlist.name}
                      </h3>
                      <Button
                        className="min-h-9 px-3 py-1.5"
                        variant={
                          playlist.containsSong === false
                            ? 'primary'
                            : 'secondary'
                        }
                        disabled={
                          busy ||
                          query.isFetching ||
                          playlist.containsSong !== false
                        }
                        aria-label={`${playlist.name} ${playlist.containsSong ? '추가됨' : playlist.containsSong === null ? '확인 필요' : '추가하기'}`}
                        onClick={() => void add(playlist)}
                      >
                        {playlist.containsSong
                          ? '추가됨 ✓'
                          : playlist.containsSong === null
                            ? '확인 필요'
                            : busy
                              ? '저장 중…'
                              : '추가하기'}
                      </Button>
                    </div>
                  ))}
                  {query.isError && items.length > 0 && (
                    <StatePanel
                      title="목록을 갱신하지 못했어요"
                      onRetry={() => void retry()}
                    />
                  )}
                  {query.hasNextPage && !query.isError && (
                    <Button
                      onClick={() => void query.fetchNextPage()}
                      disabled={busy || query.isFetching}
                    >
                      목록 더 보기
                    </Button>
                  )}
                </>
              )}
            </div>
            {!denied && (
              <Button
                className="min-h-9 self-start bg-transparent px-0 py-1.5"
                disabled={busy}
                onClick={() => setCreating(true)}
              >
                새 플레이리스트 추가하기
              </Button>
            )}
          </>
        )}
      </div>
    </Dialog>
  )
}
