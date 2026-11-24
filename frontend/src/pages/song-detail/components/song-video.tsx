import type { SongDetail } from '@/features/songs/detail-queries'
import { Artwork } from '@/shared/ui/artwork'
import { externalUrl, youtubeIdValid } from '../format'

export function SongVideo({ detail }: { detail: SongDetail }) {
  const { representativeVideo: video, song } = detail
  const validId = youtubeIdValid(video.youtubeId)
  const href = validId
    ? `https://www.youtube.com/watch?v=${video.youtubeId}`
    : externalUrl(video.url)
  return (
    <div className="flex flex-col gap-4">
      <div className="relative aspect-video w-full overflow-hidden rounded-xl bg-night">
        {video.embeddable && validId ? (
          <iframe
            title={`${song.title} YouTube 영상`}
            src={`https://www.youtube-nocookie.com/embed/${video.youtubeId}`}
            className="absolute inset-0 size-full border-0"
            allow="encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            referrerPolicy="strict-origin-when-cross-origin"
          />
        ) : (
          <Artwork
            src={song.thumbnailUrl}
            className="absolute inset-0 size-full rounded-2xl"
          />
        )}
      </div>
      {(!video.embeddable || !validId) && (
        <p className="text-sm text-muted">
          이 영상은{' '}
          {href ? (
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer"
              className="underline"
            >
              YouTube
            </a>
          ) : (
            'YouTube'
          )}
          에서 시청해 주세요.
        </p>
      )}
    </div>
  )
}
