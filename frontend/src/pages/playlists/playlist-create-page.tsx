import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { PlaylistNameForm } from '@/features/playlists/playlist-name-form'
import { PlaylistFormLayout } from './components/playlist-form-layout'

export function PlaylistCreatePage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const navigate = useNavigate()
  return (
    <PlaylistFormLayout>
      <h1 className="text-xl font-bold leading-[1.5]">새 플레이리스트</h1>
      {session.data && !session.isError && (
        <PlaylistNameForm
          key={session.data.id}
          userId={session.data.id}
          returnTo="/library/playlists/new"
          onCancel={() => void navigate('/library/playlists')}
          onDone={(playlist) =>
            void navigate(`/library/playlists/${playlist.id}`, {
              replace: true,
            })
          }
        />
      )}
    </PlaylistFormLayout>
  )
}
