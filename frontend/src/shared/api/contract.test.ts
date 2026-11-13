import { expectTypeOf, it } from 'vitest'
import type { ApiPaths } from './types'

type SongQuery = NonNullable<
  ApiPaths['/api/v1/songs']['get']['parameters']['query']
>

it('멤버 곡 조회의 참여 인원은 전체·개인·다인을 지원한다', () => {
  type MemberQuery = NonNullable<
    ApiPaths['/api/v1/members/{id}/songs']['get']['parameters']['query']
  >
  expectTypeOf<MemberQuery['participation']>().toEqualTypeOf<
    'ALL' | 'SOLO' | 'MULTIPLE' | undefined
  >()
})

it('검색 조건 타입은 실제 계약을 보존한다', () => {
  expectTypeOf<SongQuery['q']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['startDate']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['endDate']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['cursor']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['type']>().toEqualTypeOf<
    'ALL' | 'COVER' | 'ORIGINAL' | undefined
  >()
  expectTypeOf<SongQuery['sort']>().toEqualTypeOf<
    'LATEST' | 'OLDEST' | 'RELEVANCE' | 'VIEWS' | undefined
  >()
  expectTypeOf<SongQuery['mode']>().toEqualTypeOf<
    'BROWSE' | 'SEARCH' | undefined
  >()
})
