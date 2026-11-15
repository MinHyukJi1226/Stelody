import { Button } from '@/shared/ui/button'
import type { SongDetail } from '@/features/songs/detail-queries'
import { externalUrl } from '../format'

export function ExternalLinks({ links }: { links: SongDetail['links'] }) {
  return (
    <div className="flex flex-wrap gap-2">
      {links.map(({ platform, url }) => {
        const href = externalUrl(url)
        return href ? (
          <Button
            key={`${platform}-${url}`}
            asChild
            className="min-h-10 max-w-full whitespace-normal wrap-anywhere bg-bg px-3 py-2"
          >
            <a href={href} target="_blank" rel="noopener noreferrer">
              {platform} ↗
            </a>
          </Button>
        ) : null
      })}
    </div>
  )
}
