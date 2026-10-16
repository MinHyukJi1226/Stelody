import { expectTypeOf, it } from 'vitest'
import type { ApiPaths } from './types'

type SongQuery = NonNullable<
  ApiPaths['/api/v1/songs']['get']['parameters']['query']
>

it('검색 조건 타입은 실제 계약을 보존한다', () => {
  expectTypeOf<SongQuery['q']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['startDate']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['endDate']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['cursor']>().toEqualTypeOf<string | undefined>()
  expectTypeOf<SongQuery['type']>().toEqualTypeOf<
    'ALL' | 'COVER' | 'ORIGINAL' | undefined
  >()
  expectTypeOf<SongQuery['sort']>().toEqualTypeOf<
    'LATEST' | 'RELEVANCE' | 'VIEWS' | undefined
  >()
  expectTypeOf<SongQuery['mode']>().toEqualTypeOf<
    'BROWSE' | 'SEARCH' | undefined
  >()
})
