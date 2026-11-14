import { Link } from 'react-router'
import { Button } from '@/shared/ui/button'
import { cn } from '@/shared/lib/utils'

const tab = 'min-h-10 rounded-lg px-3 py-2'
const inactive = 'bg-subtle text-on-accent'

export function LibraryTabs({ playlists = false }: { playlists?: boolean }) {
  return (
    <nav aria-label="보관함 종류" className="flex flex-wrap gap-2">
      <Button
        asChild
        variant={playlists ? 'secondary' : 'primary'}
        className={cn(tab, playlists && inactive)}
      >
        <Link to="/library" aria-current={!playlists ? 'page' : undefined}>
          즐겨찾기
        </Link>
      </Button>
      <Button
        asChild
        variant={playlists ? 'primary' : 'secondary'}
        className={cn(tab, !playlists && inactive)}
      >
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
