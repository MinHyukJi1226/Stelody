import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  readCatalogGrouping,
  rememberCatalogGrouping,
} from './catalog-grouping'

beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())

it('같은 탭의 선택을 읽고 해제하면 저장값도 삭제한다', () => {
  expect(readCatalogGrouping()).toBeNull()
  rememberCatalogGrouping('MEMBER')
  expect(readCatalogGrouping()).toBe('MEMBER')
  rememberCatalogGrouping(null)
  expect(readCatalogGrouping()).toBeNull()
  expect(sessionStorage.getItem('stelody-catalog-grouping')).toBeNull()
})

it.each(['type', 'BAD', 'undefined', 'null', '{"groupBy":"TYPE"}'])(
  '조작된 저장값은 서버 조회 조건으로 사용하지 않는다: %s',
  (value) => {
    sessionStorage.setItem('stelody-catalog-grouping', value)
    expect(readCatalogGrouping()).toBeNull()
  },
)

it('저장소 차단으로 전체곡 조회나 분류 선택을 중단하지 않는다', () => {
  for (const method of ['getItem', 'setItem', 'removeItem'] as const)
    vi.spyOn(Storage.prototype, method).mockImplementation(() => {
      throw new Error('blocked')
    })
  expect(readCatalogGrouping()).toBeNull()
  expect(() => rememberCatalogGrouping('TYPE')).not.toThrow()
  expect(() => rememberCatalogGrouping(null)).not.toThrow()
})
