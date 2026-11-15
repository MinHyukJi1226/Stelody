import { useState } from 'react'
import type { SongDetail } from '@/features/songs/detail-queries'
import { Artwork } from '@/shared/ui/artwork'
import { externalUrl, youtubeIdValid } from '../format'

export function SongVideo({ detail }: { detail: SongDetail }) {
  const [playing, setPlaying] = useState(false)
  const { representativeVideo: video, song } = detail
  const validId = youtubeIdValid(video.youtubeId)
  const href = validId
    ? `https://www.youtube.com/watch?v=${video.youtubeId}`
    : externalUrl(video.url)
  return (
    <div className="flex flex-col gap-4">
      <div className="relative aspect-video w-full overflow-hidden rounded-xl bg-night">
        {playing && video.embeddable && validId ? (
          <iframe
            title={`${song.title} YouTube 영상`}
            src={`https://www.youtube-nocookie.com/embed/${video.youtubeId}?autoplay=1`}
            className="absolute inset-0 size-full border-0"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            referrerPolicy="strict-origin-when-cross-origin"
          />
        ) : (
          <>
            <Artwork
              src={song.thumbnailUrl}
              className="absolute inset-0 size-full rounded-2xl"
            />
            {video.embeddable && validId && (
              <button
                type="button"
                aria-label={`${song.title} 영상 재생`}
                onClick={() => setPlaying(true)}
                className="absolute inset-0 flex items-center justify-center bg-black/45"
              >
                <span aria-hidden="true" className="text-4xl text-white">
                  ▶
                </span>
              </button>
            )}
          </>
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
