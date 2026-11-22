import { Link } from 'react-router'
import type { MemberCard } from '@/shared/api/types'
import type { SongDetail } from '@/features/songs/detail-queries'
import { Artwork } from '@/shared/ui/artwork'

export function SongParticipants({
  song,
  members,
}: {
  song: SongDetail['song']
  members: readonly MemberCard[] | undefined
}) {
  return (
    <section aria-labelledby="participants-title" className="grid gap-3">
      <h2 id="participants-title" className="text-lg font-bold">
        참여 멤버
      </h2>
      <div className="flex flex-wrap items-center gap-2">
        {song.participants.map((participant) => {
          const name =
            participant.name +
            (participant.activityStatus === 'GRADUATED' ? ' (졸업)' : '')
          return participant.kind === 'MEMBER' ? (
            <Link
              key={`${participant.kind}-${participant.id}`}
              to={`/members/${members?.find(({ id }) => id === participant.id)?.slug ?? participant.id}`}
              aria-label={name}
              title={name}
              className="rounded-full"
            >
              <Artwork
                src={
                  members?.find(({ id }) => id === participant.id)
                    ?.profileImageUrl ?? null
                }
                avatar
                fallbackText={participant.name.slice(0, 1)}
                className="size-11 rounded-full"
              />
            </Link>
          ) : (
            <span
              key={`${participant.kind}-${participant.id}`}
              className="text-sm wrap-anywhere"
            >
              {name}
            </span>
          )
        })}
        {song.participants.length === 0 && (
          <p className="text-sm text-muted">참여자 정보 준비 중</p>
        )}
      </div>
    </section>
  )
}
