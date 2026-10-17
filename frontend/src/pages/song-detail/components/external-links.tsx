import { Button } from '@/shared/ui/button'
import type { SongDetail } from '@/features/songs/detail-queries'
import { externalUrl } from '../format'

export function ExternalLinks({
  links,
  prefix = '',
}: {
  links: SongDetail['links']
  prefix?: string
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {links.map(({ platform, url }) => {
        const href = externalUrl(url)
        return href ? (
          <Button key={`${platform}-${url}`} asChild>
            <a href={href} target="_blank" rel="noopener noreferrer">
              {prefix}
              {platform} ↗
            </a>
          </Button>
        ) : null
      })}
    </div>
  )
}
