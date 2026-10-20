import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { sessionOptions } from '@/features/auth/session'
import { PlaylistNameForm } from '@/features/playlists/playlist-name-form'
import { SiteFooter } from '@/shared/ui/site-footer'

export function PlaylistCreatePage() {
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const navigate = useNavigate()
  return (
    <div className="favorites-page flex flex-col gap-12">
      <section className="flex flex-1 flex-col gap-8">
        <p className="text-xs leading-[1.5] text-brand">PLAYLIST</p>
        <h1 className="text-[32px] font-bold leading-[1.5]">새 플레이리스트</h1>
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
      </section>
      <SiteFooter />
    </div>
  )
}
