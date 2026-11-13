import { expect, it } from 'vitest'
import {
  defaultMemberSongFilters,
  memberSongParams,
  readMemberSongFilters,
} from './song-filters'

it.each(['SOLO', 'MULTIPLE'] as const)(
  '새 참여 조건 %s는 기존 불리언 없이 URL을 왕복한다',
  (participation) => {
    const filters = {
      type: 'COVER',
      sort: 'LATEST',
      participation,
      multipleParticipants: participation === 'MULTIPLE',
    } as const
    const params = memberSongParams(filters)
    expect(params.get('participation')).toBe(participation)
    expect(params.has('multipleParticipants')).toBe(false)
    expect(params.has('collaboration')).toBe(false)
    expect(params.has('cursor')).toBe(false)
    expect(readMemberSongFilters(params).data).toEqual(filters)
  },
)

it.each([
  'participation=SOLO&multipleParticipants=false',
  'participation=SOLO&multipleParticipants=true',
  'participation=SOLO&collaboration=false',
  'participation=MULTIPLE&multipleParticipants=false',
  'participation=ALL&collaboration=true',
  'participation=UNKNOWN',
  'participation=',
])('신규 필터 오류·충돌 %s은 서버에 전달하지 않는다', (value) => {
  expect(readMemberSongFilters(new URLSearchParams(value)).success).toBe(false)
})

it('조건이 없는 멤버 주소는 전체·최신순으로 조회한다', () => {
  expect(readMemberSongFilters(new URLSearchParams()).data).toEqual(
    defaultMemberSongFilters,
  )
  expect(memberSongParams(defaultMemberSongFilters).toString()).toBe('')
})

it('곡 유형·공동 참여·정렬 조건을 URL에서 왕복하고 커서는 저장하지 않는다', () => {
  const filters = {
    type: 'COVER',
    multipleParticipants: true,
    sort: 'VIEWS',
  } as const
  const params = memberSongParams(filters)
  expect(readMemberSongFilters(params).data).toEqual(filters)
  expect(params.has('cursor')).toBe(false)
  expect(params.has('collaboration')).toBe(false)
  expect(
    readMemberSongFilters(new URLSearchParams('multipleParticipants=false'))
      .data?.multipleParticipants,
  ).toBe(false)
})

it.each([
  'type=UNKNOWN',
  'sort=RELEVANCE',
  'sort=invalid',
  'multipleParticipants=1',
  'multipleParticipants=',
  'collaboration=invalid',
  'type=',
])('잘못된 조건 %s은 서버에 전달하지 않도록 거부한다', (value) => {
  expect(readMemberSongFilters(new URLSearchParams(value)).success).toBe(false)
})

it.each(['true', 'false'])(
  '이전 collaboration=%s 주소를 새 참여 인원 필터로 읽는다',
  (value) => {
    const result = readMemberSongFilters(
      new URLSearchParams(`collaboration=${value}`),
    )
    expect(result.success).toBe(true)
    expect(result.data?.multipleParticipants).toBe(value === 'true')
    const params = memberSongParams(result.data!)
    expect(params.has('collaboration')).toBe(false)
    expect(params.get('multipleParticipants')).toBe(
      value === 'true' ? 'true' : null,
    )
  },
)

it.each([
  'multipleParticipants=true&collaboration=false',
  'multipleParticipants=false&collaboration=true',
])('신·구 필터가 충돌하는 %s 주소는 거부한다', (value) => {
  expect(readMemberSongFilters(new URLSearchParams(value)).success).toBe(false)
})

it.each(['true', 'false'])(
  '신·구 필터가 모두 %s이면 새 필터로 직렬화한다',
  (value) => {
    const result = readMemberSongFilters(
      new URLSearchParams(
        `multipleParticipants=${value}&collaboration=${value}`,
      ),
    )
    expect(result.success).toBe(true)
    expect(memberSongParams(result.data!).has('collaboration')).toBe(false)
  },
)
