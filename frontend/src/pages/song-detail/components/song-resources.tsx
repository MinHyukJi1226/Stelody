import type { SongDetail } from '@/features/songs/detail-queries'
import { externalUrl, youtubeIdValid } from '../format'
import { ExternalLinks } from './external-links'

export function KaraokeNumbers({
  entries,
}: {
  entries: SongDetail['karaoke']
}) {
  return (
    <div className="flex flex-wrap gap-2" aria-label="노래방 번호">
      {(['TJ', 'KY'] as const).map((provider) => {
        const entry = entries.find((item) => item.provider === provider)
        const label =
          entry?.status === 'REGISTERED'
            ? entry.number || '번호 확인 중'
            : entry?.status === 'NOT_LISTED'
              ? '미수록'
              : '미확인'
        return (
          <span
            key={provider}
            className="rounded-lg bg-surface p-2 text-xs leading-[1.5] text-muted"
          >
            {provider} · {label}
          </span>
        )
      })}
    </div>
  )
}

export function WorkInformation({ detail }: { detail: SongDetail }) {
  const { song, workResources } = detail
  if (song.type === 'ORIGINAL') {
    const links = detail.links.filter(
      ({ platform, url }) =>
        platform.trim().toLowerCase() !== 'youtube' && externalUrl(url),
    )
    return (
      <section
        aria-labelledby="music-title"
        className="flex min-w-0 flex-col gap-3"
      >
        <h2 id="music-title" className="text-lg font-bold">
          음원 사이트
        </h2>
        {links.length > 0 ? (
          <ExternalLinks links={links} />
        ) : (
          <p className="text-sm text-muted">등록된 음원 사이트가 없습니다.</p>
        )}
      </section>
    )
  }
  const originalVideo = workResources?.links.find(({ platform, url }) => {
    const href = externalUrl(url)
    return platform.trim().toLowerCase() === 'youtube' && href
  })
  const originalHref = originalVideo && externalUrl(originalVideo.url)
  const originalUrl = originalHref ? new URL(originalHref) : null
  const originalVideoId =
    originalUrl?.hostname === 'youtu.be'
      ? originalUrl.pathname.slice(1)
      : originalUrl &&
          ['youtube.com', 'www.youtube.com', 'm.youtube.com'].includes(
            originalUrl.hostname,
          )
        ? originalUrl.searchParams.get('v')
        : null
  return (
    <section
      aria-labelledby="work-title"
      className="flex min-w-0 flex-col gap-3"
    >
      <h2 id="work-title" className="text-lg font-bold leading-[1.5]">
        원곡 정보
      </h2>
      {song.work ? (
        <>
          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-bold wrap-anywhere">
              {song.work.title}
            </h3>
            {song.work.artists.length > 0 && (
              <p className="text-sm text-muted wrap-anywhere">
                {song.work.artists.map((artist) => artist.name).join(' · ')}
              </p>
            )}
          </div>
          {workResources && workResources.karaoke.length > 0 && (
            <KaraokeNumbers entries={workResources.karaoke} />
          )}
          {originalVideoId && youtubeIdValid(originalVideoId) && (
            <iframe
              title={`${song.work.title} 원곡 YouTube 영상`}
              src={`https://www.youtube-nocookie.com/embed/${originalVideoId}`}
              className="aspect-video w-full rounded-lg border-0 bg-night"
              loading="lazy"
              allow="encrypted-media; picture-in-picture; fullscreen"
              allowFullScreen
              referrerPolicy="strict-origin-when-cross-origin"
            />
          )}
        </>
      ) : (
        <p className="text-sm text-muted">원곡 정보 준비 중입니다.</p>
      )}
    </section>
  )
}
