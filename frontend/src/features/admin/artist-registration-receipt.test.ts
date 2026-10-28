import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  readArtistReceipt,
  storeArtistReceipt,
  clearArtistReceipt,
  type ArtistReceipt,
} from './artist-registration-receipt'
import { aid, wid } from '@/test/admin-song'
const key = `stelody-artist-registration:${aid}`
const receipt = (): ArtistReceipt => ({
  user: aid,
  requestId: wid,
  conflicted: false,
  body: {
    version: 0,
    name: '아티스트',
    aliases: ['별칭 B', '별칭 A'],
    reason: '크레딧 확인',
  },
})
beforeEach(() => {
  vi.restoreAllMocks()
  sessionStorage.clear()
})
describe('아티스트 등록 요청 보관', () => {
  it('원래 UUID와 본문·배열 순서를 바꾸지 않고 복구한다', () => {
    const value = receipt()
    storeArtistReceipt(value)
    const read = readArtistReceipt(aid)
    expect(read.state).toBe('pending')
    if (read.state !== 'pending') throw Error('missing receipt')
    expect(JSON.stringify(read.receipt)).toBe(JSON.stringify(value))
  })
  it('다른 관리자의 요청을 표시하거나 지우지 않는다', () => {
    storeArtistReceipt(receipt())
    expect(readArtistReceipt(wid)).toEqual({ state: 'none' })
    clearArtistReceipt(wid)
    expect(readArtistReceipt(aid).state).toBe('pending')
  })
  it('읽기 실패·손상·본문 불일치를 요청 없음으로 바꾸지 않는다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw Error('denied')
    })
    expect(readArtistReceipt(aid)).toEqual({ state: 'unavailable' })
    vi.restoreAllMocks()
    for (const value of [
      '{broken',
      JSON.stringify({ ...receipt(), user: wid }),
      JSON.stringify({ ...receipt(), body: { ...receipt().body, version: 1 } }),
    ]) {
      sessionStorage.setItem(key, value)
      expect(readArtistReceipt(aid)).toEqual({ state: 'invalid' })
      expect(sessionStorage.getItem(key)).toBe(value)
    }
  })
  it('저장 실패와 저장 검증 실패는 전송 가능한 상태가 아니다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('quota')
    })
    expect(() => storeArtistReceipt(receipt())).toThrow('전송하지 않았습니다')
    vi.restoreAllMocks()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => undefined)
    expect(() => storeArtistReceipt(receipt())).toThrow('전송하지 않았습니다')
  })
  it('서버의 본문 충돌 판정을 보관해 재진입 후에도 재전송하지 않는다', () => {
    storeArtistReceipt({ ...receipt(), conflicted: true })
    const read = readArtistReceipt(aid)
    expect(read.state === 'pending' && read.receipt.conflicted).toBe(true)
  })
})
