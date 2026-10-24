import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  clearWithdrawalReceipt,
  readWithdrawalReceipt,
  saveWithdrawalReceipt,
} from './withdrawal-receipt'

const receipt = {
  user: 'member',
  id: '11111111-1111-4111-8111-111111111111',
  queryToken: 'a'.repeat(43),
  expiresAt: '2100-01-01T00:00:00Z',
}
beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())

it('같은 탭에 조회 권한을 보관하고 완료 뒤 제거한다', () => {
  expect(readWithdrawalReceipt()).toEqual({ kind: 'none' })
  saveWithdrawalReceipt(receipt)
  expect(readWithdrawalReceipt()).toEqual({ kind: 'ready', receipt })
  expect(localStorage.getItem('stelody-withdrawal-result')).toBeNull()
  clearWithdrawalReceipt()
  expect(readWithdrawalReceipt()).toEqual({ kind: 'none' })
})

it('24시간 권한 만료와 손상은 결과 확인 불가로 구분한다', () => {
  saveWithdrawalReceipt({ ...receipt, expiresAt: '2000-01-01T00:00:00Z' })
  expect(readWithdrawalReceipt()).toEqual({ kind: 'expired' })
  expect(readWithdrawalReceipt()).toEqual({ kind: 'none' })
  sessionStorage.setItem('stelody-withdrawal-result', '{broken')
  expect(readWithdrawalReceipt()).toEqual({ kind: 'unavailable' })
})

it('조회 권한 저장 차단·무시를 호출자에 알린다', () => {
  const write = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
  expect(() => saveWithdrawalReceipt(receipt)).toThrow()
  write.mockImplementation(() => {})
  expect(() => saveWithdrawalReceipt(receipt)).toThrow()
})

it('조회 권한을 읽거나 제거할 수 없어도 완료로 판단하지 않는다', () => {
  saveWithdrawalReceipt(receipt)
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(readWithdrawalReceipt()).toEqual({ kind: 'unavailable' })
  expect(() => clearWithdrawalReceipt()).not.toThrow()
})
