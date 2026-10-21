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
import { PageState } from '@/shared/ui/page-state'
import { StatePanel } from '@/shared/ui/state-panel'
import { useToast } from '@/shared/ui/toast-context'
import { PlaylistSortableItems } from './playlist-sortable-items'

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
      <p className="text-xs leading-[1.5] text-brand">PRIVATE PLAYLIST</p>
      <h1 className="break-words text-[32px] font-bold leading-[1.5]">
        {playlist.name}
      </h1>
      <div className="flex flex-col gap-1 text-muted">
        <p>총 {playlist.totalCount}곡</p>
        {playlist.totalCount > playlist.availableCount && (
          <p>이용 불가 곡 {playlist.totalCount - playlist.availableCount}곡</p>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="primary"
          disabled={disabled || dirty}
          onClick={() => void navigate('/search')}
        >
          ＋ 곡 추가하기
        </Button>
        <Button
          disabled={disabled || dirty}
          onClick={() =>
            void navigate(`/library/playlists/${playlist.id}/edit`)
          }
        >
          이름 수정
        </Button>
        <Button
          variant="destructive"
          disabled={disabled || dirty}
          onClick={() => setDeleting(true)}
        >
          목록 삭제
        </Button>
      </div>
      {items.length > 0 ? (
        <>
          <div className="flex flex-col gap-4 rounded-2xl bg-tint p-4 text-sm leading-[1.5]">
            <p className="font-medium text-brand">순서 편집</p>
            <p className="text-muted">
              드래그하거나 위·아래 버튼으로 이동한 뒤 저장하세요.
            </p>
          </div>
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
        <div className="flex flex-1 items-center">
          <PageState
            kind="empty"
            title="아직 담은 곡이 없습니다"
            description="노래를 찾아 이 플레이리스트에 담아보세요."
            detail="나만 볼 수 있는 목록입니다."
            action={{ to: '/search', label: '곡 찾기' }}
          />
        </div>
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
      {items.length > 0 && (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="primary"
            disabled={!dirty || disabled}
            onClick={() => void save()}
          >
            {action.pending ? '저장 중…' : '변경 순서 저장'}
          </Button>
          <Button disabled={!dirty || busy} onClick={cancel}>
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
