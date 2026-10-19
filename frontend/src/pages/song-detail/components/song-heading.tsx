import { Link } from 'react-router'
import type { SongDetail } from '@/features/songs/detail-queries'
import { Button } from '@/shared/ui/button'
import { formatDate } from '../format'

export function SongHeading({ detail }: { detail: SongDetail }) {
  const { song } = detail
  return (
    <header className="flex min-w-0 flex-col gap-8">
      <div className="flex flex-wrap gap-3 text-xs leading-[1.5]">
        <span className="rounded-lg bg-tint p-2 text-brand">
          {song.type === 'COVER' ? '커버' : '오리지널'}
        </span>
        {song.isSpecialEvent && (
          <span className="rounded-lg bg-mint p-2 text-green">
            {song.specialEventLabel || '특별한 날'}
          </span>
        )}
      </div>
      <p className="text-xs leading-[1.5] text-brand">SONG / {song.type}</p>
      <h1 className="break-words text-[32px] font-bold leading-[1.5]">
        {song.title}
      </h1>
      <div className="flex flex-wrap gap-2" aria-label="참여 멤버와 아티스트">
        {song.participants.map((p) =>
          p.kind === 'MEMBER' ? (
            <Button
              asChild
              key={`${p.kind}-${p.id}`}
              className="max-w-full whitespace-normal break-words"
            >
              <Link to={`/members/${p.id}`}>
                {p.name}
                {p.activityStatus === 'GRADUATED' ? ' (졸업)' : ''} ›
              </Link>
            </Button>
          ) : (
            <span
              key={`${p.kind}-${p.id}`}
              className="rounded-xl bg-surface p-4 text-sm leading-[1.5]"
            >
              {p.name}
            </span>
          ),
        )}
        {song.participants.length === 0 && (
          <p className="text-sm text-muted">참여자 정보 준비 중</p>
        )}
      </div>
      <p className="text-sm leading-[1.5] text-muted">
        <time dateTime={song.publishedAt}>{formatDate(song.publishedAt)}</time>{' '}
        공개 · YouTube 조회수{' '}
        {song.viewCount === null
          ? '미수집'
          : `${song.viewCount.toLocaleString('ko-KR')}회`}
      </p>
    </header>
  )
}
