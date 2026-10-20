import { Link } from 'react-router'
import type { ReactNode } from 'react'
import { cn } from '@/shared/lib/utils'
import { Artwork } from '@/shared/ui/artwork'
import type { Song } from './api'
import type { MemberCard } from '@/shared/api/types'
import { useMemberDirectory } from '@/features/members/use-member-directory'
import { participantLabel } from './participant-label'

const publishedDate = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

export function SongCard({
  song,
  members,
  variant = 'public',
  actions,
}: {
  song: Song
  members?: readonly MemberCard[] | undefined
  variant?: 'public' | 'library'
  actions?: ReactNode
}) {
  const date = publishedDate.format(new Date(song.publishedAt))
  const origin = song.work?.artists.map(({ name }) => name).join(' · ')
  return (
    <article
      className={cn(
        'min-w-0',
        variant === 'library' &&
          'flex flex-col gap-3 rounded-2xl bg-surface p-4',
      )}
    >
      <Link
        to={`/songs/${song.id}`}
        className="flex min-w-0 flex-col gap-3 rounded-2xl"
        aria-label={`${song.title} 상세 보기`}
      >
        <Artwork
          src={song.thumbnailUrl}
          className={cn(
            'h-[180px] w-full rounded-2xl',
            variant === 'public' && 'md:h-40',
          )}
        />
        <h3
          className={cn(
            'break-words font-bold',
            variant === 'library'
              ? 'text-lg leading-[1.5]'
              : 'text-base leading-6',
          )}
        >
          {song.title}
        </h3>
        <p className="break-words text-sm leading-[1.5] text-muted">
          {participantLabel(song, members)}
        </p>
        <p className="break-words text-xs leading-[1.5] text-muted">
          {song.type === 'ORIGINAL'
            ? '공식 오리지널곡'
            : `원곡 · ${origin || song.work?.title || '정보 준비 중'}`}
        </p>
        <div className="flex min-h-[21px] flex-wrap items-center gap-2 text-xs leading-[1.5]">
          <span className="text-brand">
            {song.type === 'COVER' ? '커버' : '오리지널'}
          </span>
          <time dateTime={song.publishedAt} className="text-muted">
            {date.replace(/\. /g, '.').replace(/\.$/, '')}
          </time>
          {song.isSpecialEvent && (
            <span className="rounded-lg bg-amber px-2 py-1 text-warning">
              {song.specialEventLabel || '특별한 날'}
            </span>
          )}
        </div>
      </Link>
      {actions}
    </article>
  )
}

export function SongGrid({ songs }: { songs: readonly Song[] }) {
  const members = useMemberDirectory(
    songs.some(({ participants }) => participants.length >= 2),
  )
  return (
    <div className="grid grid-cols-1 gap-x-6 gap-y-8 md:grid-cols-2 lg:grid-cols-4">
      {songs.map((song) => (
        <SongCard key={song.id} song={song} members={members} />
      ))}
    </div>
  )
}

export function SongGridSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div role="status" aria-label="노래 불러오는 중">
      <div
        aria-hidden="true"
        className="grid grid-cols-1 gap-x-6 gap-y-8 md:grid-cols-2 lg:grid-cols-4"
      >
        {Array.from({ length: count }, (_, index) => (
          <div key={index} className="flex flex-col gap-3">
            <div className="h-[180px] rounded-2xl bg-line md:h-40" />
            <div className="h-6 w-3/4 rounded bg-line" />
            <div className="h-5 w-1/2 rounded bg-line" />
            <div className="h-[18px] w-2/3 rounded bg-line" />
            <div className="h-[18px] w-1/3 rounded bg-line" />
          </div>
        ))}
      </div>
    </div>
  )
}
