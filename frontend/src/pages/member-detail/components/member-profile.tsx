import type { CSSProperties } from 'react'
import type { MemberDetail } from '@/features/members/queries'
import { memberAccent } from '@/features/members/member-colors'
import { externalUrl } from '@/shared/lib/external-url'
import { Artwork } from '@/shared/ui/artwork'
import { Button } from '@/shared/ui/button'

export function MemberProfile({ detail }: { detail: MemberDetail }) {
  const { member, channels } = detail
  const graduated = member.activityStatus === 'GRADUATED'
  const links = [
    ...channels.map((channel) => ({
      label:
        channels.length === 1 ? '공식 YouTube' : `YouTube · ${channel.name}`,
      url: channel.url,
    })),
    { label: '치지직 채널', url: member.chzzkUrl },
    { label: 'X', url: member.xUrl },
  ].flatMap(({ label, url }) => {
    const href = url ? externalUrl(url) : undefined
    return href ? [{ label, href }] : []
  })
  const affiliation = [
    member.unitName,
    member.generation == null ? null : `${member.generation}기`,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <section
      aria-labelledby="member-name"
      style={{ '--member-accent': memberAccent(member.name) } as CSSProperties}
      className="flex min-w-0 flex-col items-start gap-4 rounded-2xl bg-tint p-6 md:bg-[linear-gradient(to_right,var(--surface)_50%,var(--member-accent))] md:p-10"
    >
      <Artwork
        src={member.profileImageUrl}
        avatar
        className="size-[72px] rounded-full"
      />
      {affiliation && (
        <p className="break-words text-xs leading-[1.5] text-brand">
          {affiliation}
        </p>
      )}
      <h1
        id="member-name"
        className="w-full break-words text-[32px] font-bold leading-[1.5]"
      >
        {member.name}
        {graduated && ' (졸업)'}
      </h1>
      <p className="text-base leading-[1.5] text-muted">
        {graduated
          ? '졸업 · 공개곡은 계속 만나볼 수 있어요.'
          : '활동 중 · 공식 채널의 공개곡 모음'}
      </p>
      {links.length > 0 && (
        <div
          className="flex w-full flex-wrap gap-2"
          aria-label="공식 채널 링크"
        >
          {links.map(({ label, href }) => (
            <Button
              key={`${label}-${href}`}
              asChild
              variant="primary"
              className="max-w-full whitespace-normal break-words"
            >
              <a href={href} target="_blank" rel="noopener noreferrer">
                {label} ↗
              </a>
            </Button>
          ))}
        </div>
      )}
    </section>
  )
}
