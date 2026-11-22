import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Navigate, useLocation, useParams } from 'react-router'
import type { Playlist } from './api'
import { playlistDetailOptions } from './queries'
import { isPlaylistPublicId, playlistPath } from './playlist-path'
import { ApiError } from '@/shared/api/errors'
import { PageState } from '@/shared/ui/page-state'

export function PlaylistAddress({
  user,
  address,
  children,
}: {
  user: string
  address: string
  children: (id: string) => ReactNode
}) {
  const short = isPlaylistPublicId(address)
  const query = useQuery({
    ...playlistDetailOptions(user, address),
    enabled: short && !!user,
  })
  if (!short) return children(address)
  const denied =
    query.error instanceof ApiError && [403, 404].includes(query.error.status)
  if (query.isError && (!query.data || denied)) {
    return (
      <PageState
        kind={denied ? 'unavailable' : 'error'}
        title={denied ? '이 목록에 접근할 수 없습니다' : '불러오지 못했어요'}
        description={
          denied
            ? '목록이 없거나 현재 계정에서 볼 수 없습니다.'
            : '연결 상태를 확인한 뒤 다시 시도해 주세요.'
        }
        action={{ to: '/library/playlists', label: '내 플레이리스트' }}
        {...(!denied ? { onRetry: () => void query.refetch() } : {})}
      />
    )
  }
  if (!query.data)
    return <div role="status" aria-label="플레이리스트 불러오는 중" />
  return children(query.data.id)
}

export function PlaylistCanonicalAddress({
  playlist,
  children,
}: {
  playlist: Playlist | undefined
  children: ReactNode
}) {
  const { playlistId = '' } = useParams()
  const location = useLocation()
  if (playlist?.publicId && playlistId !== playlist.publicId) {
    const suffix = location.pathname.slice(
      `/library/playlists/${playlistId}`.length,
    )
    return (
      <Navigate
        replace
        to={`${playlistPath(playlist)}${suffix}${location.search}${location.hash}`}
      />
    )
  }
  return children
}
