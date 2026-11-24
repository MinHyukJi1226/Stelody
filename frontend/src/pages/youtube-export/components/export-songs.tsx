import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import type { Playlist } from '@/features/playlists/api'
import { playlistPreviewOptions } from '@/features/playlists/queries'
import { participantLabel } from '@/features/songs/participant-label'
import { ApiError } from '@/shared/api/errors'

export function ExportSongs({
  user,
  playlist,
  onReload,
  busy,
}: {
  user: string
  playlist: Playlist
  onReload: () => void
  busy: boolean
}) {
  const [expanded, setExpanded] = useState(false)
  const contentId = useId()
  const query = useQuery({
    ...playlistPreviewOptions(user, playlist.id, playlist.version),
    enabled: expanded && playlist.totalCount > 0,
  })
  const items = query.data?.items.slice(0, 20) ?? []
  const members = useMemberDirectory(
    expanded && items.some(({ song }) => song && song.participants.length >= 2),
  )
  const changed =
    query.error instanceof ApiError && query.error.code === 'PLAYLIST_CHANGED'
  return (
    <div className="flex w-full min-w-0 flex-col gap-3">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={contentId}
        onClick={() => setExpanded((value) => !value)}
        className="flex min-h-11 items-center gap-4 self-start rounded-lg text-sm"
      >
        포함된 곡 보기
        <span aria-hidden="true" className="text-xs">
          {expanded ? '▼' : '▲'}
        </span>
      </button>
      <div id={contentId} hidden={!expanded}>
        {playlist.totalCount === 0 ? (
          <p className="text-muted">포함된 곡이 없습니다.</p>
        ) : query.isError ? (
          <div className="flex flex-col items-start gap-2" role="alert">
            <p className="text-muted">
              {changed
                ? '목록이 변경됐어요. 최신 목록을 다시 확인해 주세요.'
                : '포함된 곡을 불러오지 못했어요.'}
            </p>
            <button
              type="button"
              className="min-h-11 rounded-lg text-brand disabled:opacity-60"
              disabled={busy || query.isFetching}
              onClick={changed ? onReload : () => void query.refetch()}
            >
              {changed ? '최신 목록 확인' : '다시 시도'}
            </button>
          </div>
        ) : query.isPending ? (
          <p role="status" className="text-muted">
            포함된 곡을 불러오는 중…
          </p>
        ) : (
          <div className="flex min-w-0 flex-col gap-3">
            {playlist.totalCount >= 20 && (
              <p className="text-muted">최상위 20곡만 표기했어요</p>
            )}
            <ul aria-label="포함된 곡" className="flex min-w-0 flex-col gap-2">
              {items.map((item) => (
                <li key={item.id} className="break-words">
                  {item.available && item.song ? (
                    <>
                      {item.song.title}
                      <span className="text-muted">
                        {' · '}
                        {participantLabel(item.song, members)}
                      </span>
                    </>
                  ) : (
                    <span className="text-muted">
                      {item.unavailableMessage ?? '이용할 수 없는 곡'}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
