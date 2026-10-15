import { Link } from 'react-router'
import type { MemberCard } from '@/shared/api/types'
import { cn } from '@/shared/lib/utils'
import { Artwork } from '@/shared/ui/artwork'

const memberBorders: Readonly<Record<string, string>> = {
  '아야츠노 유니': 'border-member-yuni',
  '사키하네 후야': 'border-member-huya',
  '시라유키 히나': 'border-member-hina',
  '네네코 마시로': 'border-member-mashiro',
  '아카네 리제': 'border-member-lize',
  '아라하시 타비': 'border-member-tabi',
  '텐코 시부키': 'border-member-shibuki',
  '아오쿠모 린': 'border-member-rin',
  '하나코 나나': 'border-member-nana',
  '유즈하 리코': 'border-member-riko',
  '시즈키 토아': 'border-member-toa',
  '코가네 랑': 'border-member-rang',
  '하나요 히요리': 'border-member-hiyori',
  '토키노 메리': 'border-member-meri',
  '아이리 칸나': 'border-member-kanna',
}

export function MemberChip({ member }: { member: MemberCard }) {
  const graduated = member.activityStatus === 'GRADUATED'
  return (
    <Link
      to={`/members/${member.id}`}
      className={cn(
        'flex min-h-14 min-w-0 items-center gap-2.5 rounded-xl border border-line bg-surface p-3 text-sm',
        memberBorders[member.name],
      )}
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
