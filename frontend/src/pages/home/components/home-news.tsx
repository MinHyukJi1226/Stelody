import {
  cloneElement,
  useEffect,
  useRef,
  type ReactElement,
  type ReactNode,
} from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { participantLabel } from '@/features/songs/participant-label'
import type { ApiSchemas } from '@/shared/api/types'
import { Artwork } from '@/shared/ui/artwork'
import { Button } from '@/shared/ui/button'
import { milestonesOptions, upcomingOptions } from '../home-api'
import { HomeScrollingText } from './home-scrolling-text'

const number = new Intl.NumberFormat('ko-KR')
const timeZone = 'Asia/Seoul'
const kstDay = new Intl.DateTimeFormat('ko-KR', {
  timeZone,
  dateStyle: 'short',
})
const kstDate = new Intl.DateTimeFormat('ko-KR', {
  timeZone,
  month: 'long',
  day: 'numeric',
})
const kstTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone,
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const badgeClass = 'shrink-0 rounded-lg px-2 py-1 text-xs'
const thumbnailClass = 'h-[72px] w-32 rounded-lg md:h-[141px] md:w-[250px]'
const newsScrollDuration = 480

export function HomeNews() {
  const milestones = useQuery(milestonesOptions)
  const upcoming = useQuery(upcomingOptions)
  const feed = milestones.data?.status === 'READY' ? milestones.data : undefined

  const approaching =
    feed?.approaching.map(({ song, target, viewCount }) => (
      <MilestoneLink
        key={song.id}
        songId={song.id}
        title={song.title}
        artist={song.participants.length ? participantLabel(song) : ''}
        metric={`${number.format(target)}회까지 ${number.format(target - viewCount)}회 남았어요!`}
      />
    )) ?? []

  const achievements =
    feed?.achievements.map(({ song, target }) => (
      <MilestoneLink
        key={`${song.id}:${target}`}
        songId={song.id}
        title={song.title}
        artist={song.participants.length ? participantLabel(song) : ''}
        metric={`${number.format(target)}회를 달성했어요! 축하합니다!`}
      />
    )) ?? []

  const today = kstDay.format(new Date())
  const schedule =
    upcoming.data?.items.map((item) => (
      <PremiereLink key={item.youtubeId} item={item} today={today} />
    )) ?? []

  const hasViewNews = approaching.length > 0 || achievements.length > 0
  const hasViews = hasViewNews || milestones.isPending
  const showNews = schedule.length > 0 || hasViewNews
  if (
    !schedule.length &&
    !hasViews &&
    !upcoming.isPending &&
    !milestones.isError &&
    !upcoming.isError
  )
    return null

  return (
    <section
      aria-label="최신 소식"
      className={`flex min-w-0 flex-col gap-4 py-2 md:gap-6${showNews ? ' home-section-enter' : ''}`}
    >
      {schedule.length > 0 && <NewsTicker label="최초 공개" items={schedule} />}
      {upcoming.isPending && (
        <div
          aria-hidden="true"
          className="grid gap-2 motion-safe:animate-pulse"
        >
          <div className="h-[30px] w-36 rounded-lg bg-line md:h-9 md:w-52" />
          <div className="flex items-center gap-3">
            <div className={`${thumbnailClass} shrink-0 bg-line`} />
            <div className="grid gap-1">
              <div className="h-[18px] w-24 rounded-lg bg-line sm:w-[124px]" />
              <div className="h-3 w-16 rounded-lg bg-line" />
            </div>
          </div>
        </div>
      )}
      {hasViews && (
        <div className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2 md:gap-8">
          {approaching.length > 0 && (
            <NewsTicker
              label="조회수 임박"
              items={approaching}
              badge={
                <span className={`${badgeClass} bg-amber text-warning`}>
                  임박
                </span>
              }
            />
          )}
          {achievements.length > 0 && (
            <NewsTicker
              label="조회수 달성"
              items={achievements}
              badge={
                <span className={`${badgeClass} bg-mint text-green`}>달성</span>
              }
            />
          )}
          {milestones.isPending &&
            [0, 1].map((index) => (
              <div
                key={index}
                aria-hidden="true"
                className="flex h-[26px] items-center gap-2 motion-safe:animate-pulse"
              >
                <div className="h-6 w-9 rounded-lg bg-line" />
                <div className="h-3.5 w-24 rounded-lg bg-line sm:w-[116px]" />
                <div className="ml-auto h-3.5 w-[106px] rounded-lg bg-line" />
              </div>
            ))}
        </div>
      )}
      {(milestones.isError || upcoming.isError) && (
        <div className="flex flex-wrap gap-2">
          {milestones.isError && (
            <Button onClick={() => void milestones.refetch()}>
              조회수 소식 다시 불러오기
            </Button>
          )}
          {upcoming.isError && (
            <Button onClick={() => void upcoming.refetch()}>
              공개 예정 다시 불러오기
            </Button>
          )}
        </div>
      )}
    </section>
  )
}

function PremiereLink({
  item,
  today,
  tabIndex,
}: {
  item: ApiSchemas['com.stelody.schedule.PublicScheduleRepository.Item']
  today: string
  tabIndex?: number
}) {
  const start = new Date(item.scheduledStartAt)
  const day = kstDay.format(start) === today ? '오늘' : kstDate.format(start)
  return (
    <a
      href={item.url}
      target="_blank"
      rel="noreferrer"
      tabIndex={tabIndex}
      className="grid min-w-0 gap-2"
      title={`${item.title} · ${item.channelName}`}
    >
      <time
        dateTime={item.scheduledStartAt}
        className="truncate text-[22px]/[30px] font-bold text-brand md:text-[26px]/9"
      >
        {day} {kstTime.format(start)} 최초공개
      </time>
      <div className="flex min-w-0 items-center gap-3">
        <Artwork src={item.thumbnailUrl ?? null} className={thumbnailClass} />
        <div className="grid min-w-0 gap-1">
          <HomeScrollingText className="text-[18px]/[26px] md:text-[22px]/[30px] md:font-bold">
            {item.title}
          </HomeScrollingText>
          <HomeScrollingText className="text-sm/5 text-muted md:text-base/6">
            {item.channelName}
          </HomeScrollingText>
        </div>
      </div>
    </a>
  )
}

function MilestoneLink({
  songId,
  title,
  artist,
  metric,
  tabIndex,
}: {
  songId: string
  title: string
  artist: string
  metric: string
  tabIndex?: number
}) {
  return (
    <Link
      to={`/songs/${songId}`}
      tabIndex={tabIndex}
      className="flex h-full min-w-0 items-center justify-between gap-2 text-xs"
      title={[title, artist, metric].filter(Boolean).join(' · ')}
    >
      <HomeScrollingText>
        {title}
        {artist && <span className="text-muted"> {artist}</span>}
      </HomeScrollingText>
      <span className="max-w-full shrink-0 truncate">{metric}</span>
    </Link>
  )
}

function NewsTicker({
  label,
  items,
  badge,
}: {
  label: string
  badge?: ReactNode
  items: ReactElement<{ tabIndex?: number }>[]
}) {
  const viewport = useRef<HTMLDivElement>(null)
  const interval = badge ? 3000 : 5000
  const canRotate = items.length > 1

  useEffect(() => {
    const rail = viewport.current
    if (!rail || !canRotate) return
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    let frame = 0

    const timer = window.setInterval(() => {
      if (preference.matches || rail.matches(':hover, :focus-within')) return
      const size = rail.clientHeight
      if (!size) return
      const current = Math.round(rail.scrollTop / size)
      // The extra slide matches the first so resetting the loop is invisible.
      if (current >= items.length) rail.scrollTop = 0
      const destination = (current >= items.length ? 1 : current + 1) * size

      const start = rail.scrollTop
      const startedAt = performance.now()
      // Snap only after the animation so it doesn't round intermediate positions.
      rail.style.scrollSnapType = 'none'
      const step = (now: number) => {
        const progress = Math.min((now - startedAt) / newsScrollDuration, 1)
        const eased = progress * progress * (3 - 2 * progress)
        rail.scrollTop = start + (destination - start) * eased
        if (progress < 1) {
          frame = window.requestAnimationFrame(step)
        } else {
          rail.style.removeProperty('scroll-snap-type')
        }
      }
      frame = window.requestAnimationFrame(step)
    }, interval)
    return () => {
      window.clearInterval(timer)
      window.cancelAnimationFrame(frame)
      rail.style.removeProperty('scroll-snap-type')
    }
  }, [canRotate, items.length, interval])

  const slideClass = 'h-full shrink-0 snap-start'
  return (
    <div className="flex min-w-0 items-center gap-2">
      {badge}
      <div
        ref={viewport}
        tabIndex={canRotate ? 0 : undefined}
        aria-label={label}
        role="group"
        className={`flex min-w-0 flex-1 snap-y snap-mandatory flex-col overflow-x-hidden overflow-y-auto [scrollbar-width:none] ${badge ? 'h-[26px]' : 'h-[110px] md:h-[185px]'}`}
      >
        {items.map((item) => (
          <div key={item.key} className={slideClass}>
            {item}
          </div>
        ))}
        {canRotate && (
          <div
            aria-hidden="true"
            className={`${slideClass} motion-reduce:hidden`}
          >
            {cloneElement(items[0]!, { tabIndex: -1 })}
          </div>
        )}
      </div>
    </div>
  )
}
