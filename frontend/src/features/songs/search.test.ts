import { expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { createQueryClient } from '@/app/query-client'
import { server } from '@/test/server'
import { readSearchParams, searchParams } from './search-params'
import { songSearchOptions } from './search-queries'

const memberA = '00000000-0000-4000-8000-000000000001'
const memberB = '00000000-0000-4000-8000-000000000002'
it('검색어·필터·정렬은 URL로 왕복하고 멤버 중복과 순서는 정규화한다', () => {
  const expected = {
    q: '노래 & cover?',
    type: 'COVER',
    memberIds: [memberA, memberB],
    sort: 'VIEWS',
    startDate: '2025-09-01',
    endDate: '2025-09-30',
  } as const
  const params = searchParams({
    ...expected,
    memberIds: [memberB, memberA, memberA],
  })
  expect(readSearchParams(params).criteria).toEqual(expected)
  expect(params.has('cursor')).toBe(false)
})
it('검색 초기와 빈 검색어는 결과 조회를 시작하지 않는다', () => {
  expect(readSearchParams(new URLSearchParams()).active).toBe(false)
  expect(readSearchParams(new URLSearchParams('q=')).active).toBe(false)
  expect(readSearchParams(new URLSearchParams('q=노래')).criteria?.sort).toBe(
    'RELEVANCE',
  )
  expect(
    readSearchParams(new URLSearchParams('type=COVER')).criteria?.sort,
  ).toBe('LATEST')
})
it.each([
  'type=BAD',
  'sort=BAD',
  'memberIds=bad',
  'q=' + '가'.repeat(201),
  'startDate=2025-02-30',
])('잘못되거나 미지원인 URL은 넓은 조건으로 조회하지 않는다: %s', (value) => {
  const result = readSearchParams(new URLSearchParams(value))
  expect(result.criteria).toBeNull()
  expect(result.error).toBeTruthy()
})
it('검색어·조건과 불투명 커서를 다음 페이지에도 그대로 전달한다', async () => {
  const calls: URLSearchParams[] = []
  server.use(
    http.get('*/api/v1/songs', ({ request }) => {
      const params = new URL(request.url).searchParams
      calls.push(params)
      return HttpResponse.json({
        items: [],
        totalCount: 41,
        hasNext: !params.has('cursor'),
        nextCursor: params.has('cursor') ? null : 'next+/=&cursor',
      })
    }),
  )
  const client = createQueryClient()
  const criteria = {
    q: '커버곡',
    type: 'COVER',
    memberIds: [memberA],
    sort: 'LATEST',
    startDate: '2025-09-01',
    endDate: '2025-09-30',
  } as const
  try {
    await client.fetchInfiniteQuery({
      ...songSearchOptions({ ...criteria, memberIds: [...criteria.memberIds] }),
      pages: 3,
    })
    expect(calls).toHaveLength(2)
    expect(calls[0]!.get('mode')).toBe('SEARCH')
    expect(calls[0]!.get('q')).toBe('커버곡')
    expect(calls[1]!.get('cursor')).toBe('next+/=&cursor')
    expect(calls[1]!.get('type')).toBe('COVER')
    expect(calls[1]!.get('startDate')).toBe('2025-09-01')
    expect(calls[1]!.get('endDate')).toBe('2025-09-30')
    expect(calls[1]!.getAll('memberIds')).toEqual([memberA])
  } finally {
    client.clear()
  }
})
it('검색어마다 캐시가 분리되고 반복 커서는 종료한다', () => {
  const a = {
    q: '노래',
    type: 'ALL',
    memberIds: [],
    sort: 'RELEVANCE',
  } as const
  const first = songSearchOptions({ ...a, memberIds: [] })
  const next = songSearchOptions({ ...a, q: '다른 노래', memberIds: [] })
  expect(first.queryKey).not.toEqual(next.queryKey)
  expect(
    first.getNextPageParam?.(
      { items: [], totalCount: 0, hasNext: true, nextCursor: 'same' },
      [],
      null,
      [null, 'same'],
    ),
  ).toBeUndefined()
})

it.each(['', '   ', '\t'])(
  '빈 검색어 %j는 필터가 없으면 요청을 비활성화한다',
  (q) => {
    const options = songSearchOptions({
      q,
      type: 'ALL',
      memberIds: [],
      sort: 'LATEST',
    })
    expect(options.enabled).toBe(false)
    const parsed = readSearchParams(new URLSearchParams({ q, sort: 'VIEWS' }))
    expect(parsed.active).toBe(false)
    expect(parsed.error).toBe('검색어를 입력하거나 필터를 선택해 주세요.')
  },
)

it.each([
  ['1900-01-01', undefined],
  [undefined, '2100-12-31'],
  ['2024-02-29', '2024-02-29'],
])(
  '한쪽 기간·경계일·윤년 날짜를 URL에 보존한다: %s %s',
  (startDate, endDate) => {
    const criteria = {
      q: '노래',
      type: 'ALL' as const,
      memberIds: [],
      sort: 'RELEVANCE' as const,
      startDate,
      endDate,
    }
    const params = searchParams(criteria)
    const result = readSearchParams(params)
    expect(result.error).toBeNull()
    expect(result.criteria?.startDate).toBe(startDate)
    expect(result.criteria?.endDate).toBe(endDate)
  },
)
it.each([
  ['2025-02-29', undefined],
  ['2025-13-01', undefined],
  ['2025-2-01', undefined],
  ['1899-12-31', undefined],
  [undefined, '2101-01-01'],
  ['2025-10-01', '2025-09-30'],
])('잘못된 기간 URL은 요청을 차단한다: %s %s', (startDate, endDate) => {
  const params = new URLSearchParams({ q: '노래' })
  if (startDate) params.set('startDate', startDate)
  if (endDate) params.set('endDate', endDate)
  expect(readSearchParams(params).criteria).toBeNull()
  expect(readSearchParams(params).error).toBeTruthy()
})
it('기존 연도와 기간을 함께 전달한 URL을 차단한다', () => {
  const result = readSearchParams(
    new URLSearchParams('q=노래&year=2025&startDate=2025-01-01'),
  )
  expect(result.criteria).toBeNull()
  expect(result.error).toBe('연도와 날짜 범위를 함께 지정할 수 없습니다.')
})
it('기간 변경은 캐시를 분리하고 URL에 이전 커서를 남기지 않는다', () => {
  const criteria = {
    q: '노래',
    type: 'ALL' as const,
    memberIds: [],
    sort: 'RELEVANCE' as const,
    startDate: '2025-01-01',
  }
  const first = songSearchOptions(criteria)
  const next = songSearchOptions({ ...criteria, startDate: '2025-02-01' })
  expect(first.queryKey).not.toEqual(next.queryKey)
  expect(next.initialPageParam).toBeNull()
  expect(
    searchParams({ ...criteria, startDate: '2025-02-01' }).has('cursor'),
  ).toBe(false)
})

it.each([
  { type: 'COVER' as const },
  { type: 'ORIGINAL' as const },
  { memberIds: [memberA] },
  { startDate: '2025-09-01' },
  { endDate: '2025-09-30' },
])('검색어 없이 필터만 있으면 BROWSE로 요청한다: %j', async (filter) => {
  let submitted: URLSearchParams | undefined
  server.use(
    http.get('*/api/v1/songs', ({ request }) => {
      submitted = new URL(request.url).searchParams
      return HttpResponse.json({
        items: [],
        totalCount: 0,
        hasNext: false,
        nextCursor: null,
      })
    }),
  )
  const criteria = {
    q: '   ',
    type: 'ALL' as const,
    memberIds: [],
    sort: 'LATEST' as const,
    ...filter,
  }
  const parsed = readSearchParams(searchParams(criteria))
  expect(parsed.active).toBe(true)
  expect(parsed.error).toBeNull()
  const options = songSearchOptions(parsed.criteria!)
  expect(options.enabled).toBe(true)
  const client = createQueryClient()
  try {
    await client.fetchInfiniteQuery(options)
    expect(submitted?.get('mode')).toBe('BROWSE')
    expect(submitted?.has('q')).toBe(false)
    expect(submitted?.get('sort')).toBe('LATEST')
  } finally {
    client.clear()
  }
})
