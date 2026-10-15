import { expect, it } from 'vitest'
import type { MemberCard } from '@/shared/api/types'
import type { Song } from './api'
import { participantLabel } from './participant-label'

const yuni = '아야츠노 유니',
  kanna = '아이리 칸나',
  huya = '사키하네 후야'
const second = ['히나', '마시로', '리제', '타비']
const third = ['시부키', '린', '나나', '리코']
const fourth = ['토아', '랑', '히요리', '메리']
const members: MemberCard[] = [
  ...[yuni, huya, kanna].map((name) => ({
    name,
    generation: 1,
    unitName: 'Everys',
  })),
  ...second.map((name) => ({ name, generation: 2, unitName: 'Universe' })),
  ...third.map((name) => ({ name, generation: 3, unitName: 'Cliché' })),
  ...fourth.map((name) => ({ name, generation: 4, unitName: 'ABYSS' })),
].map((m) => ({
  ...m,
  id: m.name,
  activityStatus: m.name === kanna ? 'GRADUATED' : 'ACTIVE',
  profileImageUrl: null,
  chzzkUrl: null,
  xUrl: null,
  songCounts: { total: 0, covers: 0, originals: 0, collaborations: 0 },
}))
function song(
  names: readonly string[],
  publishedAt = '2026-10-15T00:00:00+09:00',
): Pick<Song, 'participants' | 'publishedAt' | 'type'> {
  return {
    type: 'COVER',
    publishedAt,
    participants: names.map((name) => ({
      id: name,
      name,
      kind: 'MEMBER',
      activityStatus: 'ACTIVE',
    })),
  }
}
it.each([
  ['2024-12-02T14:59:59Z', [yuni, kanna], 'Mystic'],
  ['2024-12-02T15:00:00Z', [yuni, kanna], `${yuni} · ${kanna}`],
  ['2025-09-19T14:59:59Z', [yuni, huya], `${yuni} · ${huya}`],
  ['2025-09-19T15:00:00Z', [yuni, huya], 'Everys'],
  ['2025-05-01T00:00:00Z', [yuni], yuni],
])('%s의 1기 이력을 적용한다', (date, names, label) => {
  expect(participantLabel(song(names, date), members)).toBe(label)
})
it.each([
  ['2024-12-02', [yuni, kanna]],
  ['2025-05-01', [yuni]],
  ['2025-09-20', [yuni, huya]],
])('%s 당시 전원 참여를 표시한다', (date, first) => {
  expect(
    participantLabel(
      song([...first, ...second, ...third], `${date}T12:00:00+09:00`),
      members,
    ),
  ).toBe('스텔라이브')
})
it('4기 데뷔일은 한국 시간 2026-10-18부터 적용한다', () => {
  const names = [yuni, huya, ...second, ...third]
  expect(participantLabel(song(names, '2026-10-17T14:59:59Z'), members)).toBe(
    '스텔라이브',
  )
  expect(participantLabel(song(names, '2026-10-17T15:00:00Z'), members)).toBe(
    'Everys · Universe · Cliché',
  )
  expect(
    participantLabel(
      song([...names, ...fourth], '2026-10-17T15:00:00Z'),
      members,
    ),
  ).toBe('스텔라이브')
})
it.each([
  [second, 'Universe'],
  [third, 'Cliché'],
] as const)('고정 유닛의 전원을 비교한다: %s', (names, unit) => {
  expect(participantLabel(song([...names].reverse()), members)).toBe(unit)
})
it('일부만 참여한 유닛은 이름을 나열한다', () => {
  expect(participantLabel(song(second.slice(0, 3)), members)).toBe(
    '히나 · 마시로 · 리제',
  )
})
it('다른 참여자가 추가되면 첫 참여자 외 N명으로 표시한다', () => {
  expect(participantLabel(song([yuni, ...second]), members)).toBe(
    `${yuni} 외 4명`,
  )
})
it('외부 참여자는 유닛 전원으로 판정하지 않는다', () => {
  const data = song(second)
  data.participants[0]!.kind = 'EXTERNAL'
  expect(participantLabel(data, members)).toBe('히나 외 3명')
})
it('명단이 없으면 유닛을 추측하지 않는다', () => {
  expect(participantLabel(song(second))).toBe('히나 외 3명')
})
it('중복 참여자를 인원수에 포함하지 않는다', () => {
  expect(participantLabel(song([...second, second[0]!]), members)).toBe(
    'Universe',
  )
})
it('참여자가 없으면 준비 중을 표시한다', () => {
  expect(participantLabel(song([]), members)).toBe('참여자 정보 준비 중')
})

it.each([second.slice(0, 3), [yuni, ...second]])(
  '유닛곡이 아닌 오리지널은 스텔라이브로 표시한다: %s',
  (...names) => {
    expect(
      participantLabel({ ...song(names), type: 'ORIGINAL' }, members),
    ).toBe('스텔라이브')
  },
)

it('오리지널도 유닛 전원만 참여하면 유닛명으로 표시한다', () => {
  expect(participantLabel({ ...song(second), type: 'ORIGINAL' }, members)).toBe(
    'Universe',
  )
})

it.each(['ORIGINAL', 'COVER'] as const)(
  '%s도 솔로·유닛이 아닌 2인곡의 이름 표시는 유지한다',
  (type) => {
    expect(participantLabel({ ...song([yuni]), type }, members)).toBe(yuni)
    expect(
      participantLabel({ ...song([yuni, second[0]!]), type }, members),
    ).toBe(`${yuni} · ${second[0]}`)
    expect(participantLabel({ ...song([]), type }, members)).toBe(
      '참여자 정보 준비 중',
    )
  },
)

it.each(['COVER', 'ORIGINAL'] as const)(
  '%s도 완성된 복수 유닛만 참여하면 유닛명을 이어 표시한다',
  (type) => {
    const data = {
      ...song(
        [second[0]!, yuni, ...second.slice(1), kanna],
        '2024-11-24T12:00:00+09:00',
      ),
      type,
    }
    expect(participantLabel(data, members)).toBe('Mystic · Universe')
  },
)

it('여러 유닛 중 일부 구성원이 빠지면 유닛명으로 묶지 않는다', () => {
  expect(
    participantLabel(
      song([yuni, kanna, ...second.slice(0, 3)], '2024-11-24T12:00:00+09:00'),
      members,
    ),
  ).toBe(`${yuni} 외 4명`)
})

it('점묘의 노래의 공개일과 참여자는 Mystic으로 표시한다', () => {
  expect(
    participantLabel(song([yuni, kanna], '2024-12-01T12:00:00+09:00'), members),
  ).toBe('Mystic')
})
