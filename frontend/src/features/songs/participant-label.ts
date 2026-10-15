import type { MemberCard } from '@/shared/api/types'
import type { Song } from './api'

const yuni = '아야츠노 유니'
const kanna = '아이리 칸나'
const huya = '사키하네 후야'
const firstGeneration = new Set([yuni, kanna, huya])
const dateFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

// User-provided membership history; end dates include the entire Korean day.
function firstGenerationNames(date: string) {
  if (date <= '2024-12-02') return [yuni, kanna]
  if (date < '2025-09-20') return [yuni]
  return [yuni, huya]
}

export function participantLabel(
  song: Pick<Song, 'participants' | 'publishedAt' | 'type'>,
  members?: readonly MemberCard[],
): string {
  const participants = song.participants.filter(
    (p, index, all) =>
      all.findIndex((other) => other.name === p.name) === index,
  )
  const individualNames =
    participants.map(({ name }) => name).join(' · ') || '참여자 정보 준비 중'
  if (participants.length < 2) return individualNames
  const names = new Set(participants.map(({ name }) => name))
  const date = dateFormat.format(new Date(song.publishedAt))
  const matches = (roster: readonly string[]) =>
    roster.length > 0 &&
    roster.length === names.size &&
    roster.every((name) => names.has(name))
  const membersOnly = participants.every(({ kind }) => kind === 'MEMBER')

  if (membersOnly) {
    const first = firstGenerationNames(date)
    if (members?.length) {
      const activeNames = members
        .filter((m) =>
          firstGeneration.has(m.name)
            ? first.includes(m.name)
            : m.activityStatus === 'ACTIVE' &&
              (m.generation !== 4 || date >= '2026-10-18'),
        )
        .map(({ name }) => name)
      if (matches(activeNames)) return '스텔라이브'
    }
    const units = new Map<string, string[]>()
    if (first.length === 2) {
      units.set(date <= '2024-12-02' ? 'Mystic' : 'Everys', first)
    }
    if (members?.length) {
      for (const member of members) {
        if (
          firstGeneration.has(member.name) ||
          !member.unitName ||
          (member.generation === 4 && date < '2026-10-18')
        )
          continue
        const roster = units.get(member.unitName) ?? []
        roster.push(member.name)
        units.set(member.unitName, roster)
      }
    }
    const completeUnits = [...units].filter(
      ([, roster]) =>
        roster.length > 1 && roster.every((name) => names.has(name)),
    )
    const unitMembers = new Set(completeUnits.flatMap(([, roster]) => roster))
    if (unitMembers.size === names.size) {
      if (completeUnits.length > 1 && song.type === 'ORIGINAL')
        return '스텔라이브'
      return completeUnits.map(([unit]) => unit).join(' · ')
    }
  }
  if (participants.length < 3) return individualNames
  if (song.type === 'ORIGINAL') return '스텔라이브'
  if (participants.length >= 4) {
    return `${participants[0]!.name} 외 ${participants.length - 1}명`
  }
  return individualNames
}
