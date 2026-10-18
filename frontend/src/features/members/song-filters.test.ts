import { expect, it } from 'vitest'
import {
  defaultMemberSongFilters,
  memberSongParams,
  readMemberSongFilters,
} from './song-filters'

it('조건이 없는 멤버 주소는 전체·최신순으로 조회한다', () => {
  expect(readMemberSongFilters(new URLSearchParams()).data).toEqual(
    defaultMemberSongFilters,
  )
  expect(memberSongParams(defaultMemberSongFilters).toString()).toBe('')
})

it('곡 유형·공동 참여·정렬 조건을 URL에서 왕복하고 커서는 저장하지 않는다', () => {
  const filters = { type: 'COVER', collaboration: true, sort: 'VIEWS' } as const
  const params = memberSongParams(filters)
  expect(readMemberSongFilters(params).data).toEqual(filters)
  expect(params.has('cursor')).toBe(false)
  expect(
    readMemberSongFilters(new URLSearchParams('collaboration=false')).data
      ?.collaboration,
  ).toBe(false)
})

it.each([
  'type=UNKNOWN',
  'sort=RELEVANCE',
  'sort=invalid',
  'collaboration=1',
  'collaboration=',
  'type=',
])('잘못된 조건 %s은 서버에 전달하지 않도록 거부한다', (value) => {
  expect(readMemberSongFilters(new URLSearchParams(value)).success).toBe(false)
})
