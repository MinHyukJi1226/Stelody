import type { MemberDetail } from '@/features/members/queries'
import { externalUrl } from '@/shared/lib/external-url'
import { Artwork } from '@/shared/ui/artwork'
import youtubeIcon from '@/shared/assets/social/youtube-icon.png'
import chzzkIcon from '@/shared/assets/social/chzzk-icon.png'
import xLogo from '@/shared/assets/social/x-logo.svg'

export function MemberProfile({ detail }: { detail: MemberDetail }) {
  const { member, channels } = detail
  const links = [
    ...channels.map((channel) => ({
      kind: 'youtube',
      label:
        channels.length === 1 ? '공식 YouTube' : `YouTube · ${channel.name}`,
      url: channel.url,
    })),
    { kind: 'chzzk', label: '치지직 채널', url: member.chzzkUrl },
    { kind: 'x', label: 'X', url: member.xUrl },
  ].flatMap(({ kind, label, url }) => {
    const href = url ? externalUrl(url) : undefined
    return href ? [{ kind, label, href }] : []
  })
  return (
    <section
      aria-labelledby="member-name"
      className="home-section-enter flex min-w-0 items-center gap-5"
    >
      <Artwork
        src={member.profileImageUrl}
        avatar
        className="size-24 rounded-full"
      />
      <div className="flex min-w-0 flex-col gap-1">
        <h1
          id="member-name"
          className="break-words text-2xl font-bold leading-[1.5]"
        >
          {member.name}
          {member.activityStatus === 'GRADUATED' && (
            <span className="sr-only"> (졸업)</span>
          )}
        </h1>
        {(member.activityStatus === 'GRADUATED' || member.unitName) && (
          <p className="break-words text-sm">
            {member.activityStatus === 'GRADUATED'
              ? 'GRADUATE'
              : member.unitName}
          </p>
        )}
        {links.length > 0 && (
          <div className="flex flex-wrap gap-2" aria-label="공식 채널 링크">
            {links.map(({ kind, label, href }) => (
              <a
                key={`${label}-${href}`}
                href={href}
                aria-label={label}
                title={label}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex size-8 shrink-0 items-center justify-center overflow-hidden rounded-full bg-black transition-opacity hover:opacity-75"
                style={
                  kind === 'youtube'
                    ? { backgroundColor: '#ff0033' }
                    : undefined
                }
              >
                <img
                  src={
                    kind === 'youtube'
                      ? youtubeIcon
                      : kind === 'chzzk'
                        ? chzzkIcon
                        : xLogo
                  }
                  alt=""
                  width={kind === 'youtube' ? 40 : kind === 'chzzk' ? 32 : 16}
                  height={kind === 'youtube' ? 40 : kind === 'chzzk' ? 32 : 16}
                  className={`max-w-none shrink-0 object-contain ${kind === 'youtube' ? 'size-10' : kind === 'chzzk' ? 'size-8' : 'size-4'}`}
                />
              </a>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
