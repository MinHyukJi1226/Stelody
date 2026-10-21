import { useState } from 'react'
import { useNavigate } from 'react-router'
import type { PlaylistSnapshot } from '@/features/playlists/editor-snapshot'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { PlaylistDeleteDialog } from '@/features/playlists/playlist-delete-dialog'
import { usePlaylistManagement } from '@/features/playlists/use-playlist-management'
import {
  isUncertainPlaylistWrite,
  playlistErrorMessage,
} from '@/features/playlists/queries'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { PageState } from '@/shared/ui/page-state'
import { StatePanel } from '@/shared/ui/state-panel'
import { useToast } from '@/shared/ui/toast-context'
import { PlaylistSong } from './playlist-song'

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
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string>()
  const [recovery, setRecovery] = useState(false)
  const [reloading, setReloading] = useState(false)
  const navigate = useNavigate()
  const notify = useToast()
  const playlist = snapshot.playlist
  const items = snapshot.items
  const action = usePlaylistManagement(
    userId,
    `/library/playlists/${playlist.id}`,
  )
  const conflict =
    readError instanceof ApiError && readError.code === 'PLAYLIST_CHANGED'
  const busy = action.pending || reloading
  const disabled =
    busy || fetching || !!readError || recovery || conflict || deleting
  const members = useMemberDirectory(
    items.some((i) => i.song && i.song.participants.length >= 2),
  )
  async function reload() {
    if (busy) return
    setReloading(true)
    try {
      await onReload()
      setRecovery(false)
      setError(undefined)
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
  async function remove(item: (typeof items)[number]) {
    if (disabled) return
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
          disabled={disabled}
          onClick={() => void navigate('/search')}
        >
          ＋ 곡 추가하기
        </Button>
        <Button
          disabled={disabled}
          onClick={() =>
            void navigate(`/library/playlists/${playlist.id}/edit`)
          }
        >
          이름 수정
        </Button>
        <Button
          variant="destructive"
          disabled={disabled}
          onClick={() => setDeleting(true)}
        >
          목록 삭제
        </Button>
      </div>
      {items.length > 0 ? (
        <div
          role="list"
          aria-label="플레이리스트 곡 순서"
          className="flex min-w-0 flex-col gap-8"
        >
          {items.map((item) => (
            <article
              key={item.id}
              role="listitem"
              className={`flex min-w-0 flex-col gap-4 rounded-2xl p-4 ${item.available && item.song ? 'bg-surface md:flex-row md:items-center md:gap-3' : 'bg-bg'}`}
            >
              {item.available && item.song ? (
                <PlaylistSong song={item.song} members={members} />
              ) : (
                <div className="flex min-w-0 flex-col gap-1">
                  <h2 className="text-sm leading-[1.5]">
                    현재 이용할 수 없는 곡
                  </h2>
                  <p className="text-xs leading-[1.5] text-muted">
                    {item.unavailableMessage || '저장한 순서는 유지됩니다.'}
                  </p>
                </div>
              )}
              <div>
                <Button
                  disabled={disabled}
                  aria-label={`${item.available && item.song ? item.song.title : '이용 불가 곡'} 목록에서 제거`}
                  onClick={() => void remove(item)}
                >
                  {item.available && item.song ? '제거' : '목록에서 제거'}
                </Button>
              </div>
            </article>
          ))}
        </div>
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
              ? '전체 목록을 다시 불러와 최신 내용을 확인해 주세요.'
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
