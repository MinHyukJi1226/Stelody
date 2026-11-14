import { useState } from 'react'
import { useNavigate } from 'react-router'
import { arrayMove } from '@dnd-kit/sortable'
import type { PlaylistSnapshot } from '@/features/playlists/editor-snapshot'
import type { PlaylistItem } from '@/features/playlists/api'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { PlaylistDeleteDialog } from '@/features/playlists/playlist-delete-dialog'
import { usePlaylistManagement } from '@/features/playlists/use-playlist-management'
import { usePlaylistLeaveLock } from '@/features/playlists/use-playlist-leave-lock'
import {
  isUncertainPlaylistWrite,
  playlistErrorMessage,
} from '@/features/playlists/queries'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { StatePanel } from '@/shared/ui/state-panel'
import { useToast } from '@/shared/ui/toast-context'
import { PlaylistSortableItems } from './playlist-sortable-items'
import { PlaylistActionIcon } from './playlist-action-icon'

type Draft = { base: PlaylistSnapshot; items: PlaylistItem[] }
export function PlaylistEditor({
  userId,
  snapshot,
  fetching,
  readError,
  onReload,
}: {
  userId: string
  snapshot: PlaylistSnapshot
  fetching: boolean
  readError: Error | null
  onReload: () => Promise<PlaylistSnapshot>
}) {
  const [draft, setDraft] = useState<Draft>()
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string>()
  const [recovery, setRecovery] = useState(false)
  const [reloading, setReloading] = useState(false)
  const navigate = useNavigate()
  const notify = useToast()
  const base = draft?.base ?? snapshot
  const playlist = base.playlist
  const items = draft?.items ?? snapshot.items
  const dirty = !!draft
  const action = usePlaylistManagement(
    userId,
    `/library/playlists/${playlist.id}`,
  )
  const blocker = usePlaylistLeaveLock(userId, dirty)
  const conflict =
    (dirty && snapshot.playlist.version !== playlist.version) ||
    (readError instanceof ApiError && readError.code === 'PLAYLIST_CHANGED')
  const busy = action.pending || reloading
  const disabled =
    busy || fetching || !!readError || recovery || conflict || deleting
  const members = useMemberDirectory(
    items.some((i) => i.song && i.song.participants.length >= 2),
  )
  function move(from: number, to: number) {
    if (
      disabled ||
      from < 0 ||
      to < 0 ||
      from >= items.length ||
      to >= items.length ||
      from === to
    )
      return
    const next = arrayMove(items, from, to)
    const restored = next.every(
      (item, index) => item.id === base.items[index]?.id,
    )
    if (restored && blocker.state === 'blocked') blocker.reset()
    setDraft(restored ? undefined : { base, items: next })
  }
  function cancel() {
    if (busy) return
    setDraft(undefined)
    if (blocker.state === 'blocked') blocker.reset()
  }
  async function reload() {
    if (busy) return
    setReloading(true)
    try {
      await onReload()
      setDraft(undefined)
      setRecovery(false)
      setError(undefined)
      if (blocker.state === 'blocked') blocker.reset()
    } catch (failure) {
      setError(playlistErrorMessage(failure))
    } finally {
      setReloading(false)
    }
  }
  function fail(failure: unknown) {
    setError(playlistErrorMessage(failure))
    setRecovery(
      isUncertainPlaylistWrite(failure) ||
        (failure instanceof ApiError && [404, 409].includes(failure.status)),
    )
  }
  async function save() {
    if (!dirty || disabled) return
    setError(undefined)
    try {
      const result = await action.write({
        kind: 'order',
        playlist,
        itemIds: items.map((i) => i.id),
      })
      if (result?.kind === 'saved') {
        setDraft(undefined)
        setRecovery(false)
        notify('플레이리스트 순서를 저장했어요.')
        if (blocker.state === 'blocked') blocker.reset()
      }
    } catch (failure) {
      fail(failure)
    }
  }
  async function remove(item: PlaylistItem) {
    if (dirty || disabled) return
    setError(undefined)
    try {
      const result = await action.write({
        kind: 'remove',
        playlist,
        itemId: item.id,
        songId: item.songId,
      })
      if (result?.kind === 'saved') notify('목록에서 곡을 제거했어요.')
    } catch (failure) {
      fail(failure)
    }
  }
  return (
    <>
      <header className="home-section-enter flex min-w-0 flex-col gap-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <h1 className="min-w-0 wrap-anywhere text-xl font-bold md:text-2xl">
            {playlist.name}
          </h1>
          <p className="text-xs text-muted">총 {playlist.totalCount}곡</p>
          <Button
            size="icon"
            className="size-9 rounded-xl border-0 bg-[#00e600] text-white"
            disabled={disabled || dirty}
            aria-label="이름 수정"
            onClick={() =>
              void navigate(`/library/playlists/${playlist.id}/edit`)
            }
          >
            <PlaylistActionIcon kind="edit" />
          </Button>
          <Button
            size="icon"
            variant="destructive"
            className="size-9 rounded-xl border-0 bg-[#ff4d4f] text-white"
            disabled={disabled || dirty}
            aria-label="목록 삭제"
            onClick={() => setDeleting(true)}
          >
            <PlaylistActionIcon kind="delete" />
          </Button>
        </div>
        {playlist.totalCount > playlist.availableCount && (
          <p className="text-xs text-muted">
            이용 불가 곡 {playlist.totalCount - playlist.availableCount}곡
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <Button
            variant="primary"
            className="min-h-10 rounded-lg px-3 py-2"
            disabled={disabled || dirty}
            onClick={() => void navigate('/songs')}
          >
            곡 추가하기
          </Button>
          <Button
            variant="primary"
            className="min-h-10 rounded-lg px-3 py-2"
            disabled={disabled || dirty}
            onClick={() =>
              void navigate(`/library/playlists/${playlist.id}/youtube-export`)
            }
          >
            YouTube로 내보내기
          </Button>
        </div>
      </header>
      {items.length > 0 ? (
        <>
          {blocker.state === 'blocked' && (
            <p role="alert" className="text-sm text-error">
              {busy
                ? '저장 중입니다. 완료 후 이동해 주세요.'
                : '순서 변경을 저장하거나 취소한 뒤 이동해 주세요.'}
            </p>
          )}
          <PlaylistSortableItems
            items={items}
            members={members}
            disabled={disabled}
            removalLocked={dirty}
            onMove={move}
            onRemove={(item) => void remove(item)}
          />
        </>
      ) : (
        <h2 className="home-section-enter text-lg font-bold leading-[1.5]">
          아직 담은 곡이 없습니다
        </h2>
      )}
      {(error || readError || conflict) && (
        <StatePanel
          title={
            conflict ? '목록이 변경되었습니다' : '요청을 처리하지 못했어요'
          }
          description={
            conflict || recovery
              ? '저장하지 않은 순서를 취소하고 전체 목록을 다시 불러와 확인해 주세요.'
              : error || '이미 불러온 곡은 유지됩니다.'
          }
          onRetry={() => void reload()}
        />
      )}
      {error && recovery && (
        <p role="alert" className="text-sm text-error">
          {error}
        </p>
      )}
      {dirty && (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="primary"
            disabled={disabled}
            onClick={() => void save()}
          >
            {action.pending ? '저장 중…' : '변경 순서 저장'}
          </Button>
          <Button disabled={busy} onClick={cancel}>
            취소
          </Button>
        </div>
      )}
      {deleting && (
        <PlaylistDeleteDialog
          userId={userId}
          playlist={snapshot.playlist}
          onClose={() => setDeleting(false)}
          onReload={() => void reload()}
        />
      )}
    </>
  )
}
