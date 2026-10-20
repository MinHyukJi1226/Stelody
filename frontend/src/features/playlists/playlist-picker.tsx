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
    <Dialog labelledBy={titleId} busy={busy} onDismiss={onClose}>
      <div className="flex flex-col items-start gap-4">
        <div className="flex w-full items-start gap-3">
          <h2
            id={titleId}
            className={`min-w-0 flex-1 break-words font-bold leading-[1.5] ${creating ? 'text-2xl' : 'text-xl md:text-2xl'}`}
          >
            {creating ? '새 플레이리스트' : '플레이리스트에 추가'}
          </h2>
          {!creating && (
            <Button disabled={busy} onClick={onClose}>
              닫기
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
            <p className="text-sm leading-[1.5] text-muted">
              추가할 플레이리스트를 선택하세요.
            </p>
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
                    className={`flex w-full flex-col items-start gap-2 rounded-xl p-4 ${playlist.containsSong === false ? 'bg-tint' : 'bg-bg'}`}
                  >
                    <h3 className="w-full break-words font-bold leading-6">
                      {playlist.name}
                    </h3>
                    <p className="text-sm leading-[1.5] text-muted">
                      {playlist.containsSong
                        ? '이미 담긴 곡'
                        : playlist.containsSong === false
                          ? '이 목록에 노래를 추가합니다.'
                          : '포함 여부를 다시 확인해 주세요.'}
                    </p>
                    <Button
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
                      aria-label={`${playlist.name} ${playlist.containsSong ? '추가됨' : '이 목록에 담기'}`}
                      onClick={() => void add(playlist)}
                    >
                      {playlist.containsSong
                        ? '추가됨 ✓'
                        : busy
                          ? '저장 중…'
                          : '이 목록에 담기'}
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
            {!denied && (
              <Button disabled={busy} onClick={() => setCreating(true)}>
                ＋ 새 플레이리스트
              </Button>
            )}
          </>
        )}
      </div>
    </Dialog>
  )
}
