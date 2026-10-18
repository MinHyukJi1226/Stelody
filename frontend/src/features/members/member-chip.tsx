import { Link } from 'react-router'
import type { MemberCard } from '@/shared/api/types'
import { cn } from '@/shared/lib/utils'
import { Artwork } from '@/shared/ui/artwork'
import { memberAccent } from './member-colors'

export function MemberChip({ member }: { member: MemberCard }) {
  const graduated = member.activityStatus === 'GRADUATED'
  return (
    <Link
      to={`/members/${member.id}`}
      className="flex min-h-14 min-w-0 items-center gap-2.5 rounded-xl border bg-surface p-3 text-sm font-medium"
      style={{ borderColor: memberAccent(member.name) }}
    >
      <Artwork
        src={member.profileImageUrl}
        avatar
        className="size-8 rounded-full"
      />
      <span className={cn('break-words', graduated && 'text-muted')}>
        {member.name}
        {graduated && ' (졸업)'}
      </span>
    </Link>
  )
}
