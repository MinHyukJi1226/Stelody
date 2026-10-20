import { Link } from 'react-router'
import { Button } from '@/shared/ui/button'

export function LibraryTabs({ playlists = false }: { playlists?: boolean }) {
  return (
    <nav aria-label="보관함 종류" className="flex flex-wrap gap-2">
      <Button asChild variant={playlists ? 'secondary' : 'primary'}>
        <Link to="/library" aria-current={!playlists ? 'page' : undefined}>
          즐겨찾기
        </Link>
      </Button>
      <Button asChild variant={playlists ? 'primary' : 'secondary'}>
        <Link
          to="/library/playlists"
          aria-current={playlists ? 'page' : undefined}
        >
          플레이리스트
        </Link>
      </Button>
    </nav>
  )
}
