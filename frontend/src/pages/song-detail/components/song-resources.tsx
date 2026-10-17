import type { SongDetail } from '@/features/songs/detail-queries'
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
  if (song.type === 'ORIGINAL') return null
  return (
    <section
      aria-labelledby="work-title"
      className="flex flex-col gap-4 rounded-2xl bg-surface p-6"
    >
      <h2 id="work-title" className="text-2xl font-bold leading-[1.5]">
        원곡 정보
      </h2>
      {song.work ? (
        <>
          <h3 className="break-words text-lg font-bold">{song.work.title}</h3>
          <p className="text-muted">
            원곡 아티스트 ·{' '}
            {song.work.artists.map((a) => a.name).join(' · ') || '정보 준비 중'}
          </p>
          {workResources && (
            <>
              <ExternalLinks links={workResources.links} prefix="원곡 " />
              {workResources.karaoke.length > 0 && (
                <div className="flex flex-col gap-2">
                  <p className="text-sm text-muted">원곡 노래방 번호</p>
                  <KaraokeNumbers entries={workResources.karaoke} />
                </div>
              )}
            </>
          )}
        </>
      ) : (
        <p className="text-sm text-muted">원곡 정보 준비 중입니다.</p>
      )}
    </section>
  )
}
