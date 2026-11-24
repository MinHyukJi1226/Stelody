import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  beginLogin,
  clearLoginReturn,
  readLoginReturn,
  rememberLoginReturn,
  safeReturnPath,
} from './return-path'
import { googleLoginUrl } from './api'

describe('로그인 복귀 경로', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('로컬 개발에서만 loginClient를 지정하고 운영 요청에는 생략한다', () => {
    vi.stubEnv('DEV', true)
    const local = new URL(googleLoginUrl(), 'http://localhost:5173')
    expect(local.searchParams.get('loginClient')).toBe('local')
    vi.stubEnv('DEV', false)
    const production = new URL(googleLoginUrl(), 'https://stelody.example')
    expect(production.searchParams.has('loginClient')).toBe(false)
    expect(production.searchParams.get('returnTo')).toBe(
      local.searchParams.get('returnTo'),
    )
  })
  it('검색 조건·중복 파라미터·해시를 보존하고 인증 안내 파라미터만 제거한다', () => {
    const value =
      '/search?q=%ED%95%9C%EA%B8%80&memberIds=a&memberIds=b&loginResult=success#results'
    const result = safeReturnPath(value)
    const url = new URL(result, 'https://stelody.invalid')
    expect(url.searchParams.get('q')).toBe('한글')
    expect(url.searchParams.getAll('memberIds')).toEqual(['a', 'b'])
    expect(url.searchParams.has('loginResult')).toBe(false)
    expect(url.hash).toBe('#results')
  })

  it.each([
    null,
    '',
    'https://evil.example',
    '//evil.example',
    '/\\evil.example',
    '/api/v1/me',
    '/API',
    '/actuator/health',
    '/login?returnTo=/login',
    '/songs/../api',
    '/%2e%2e/api',
    '/%2F%2Fevil.example',
    '/%252fapi',
    '/songs;a=b',
    '/songs%0a',
    '/songs\n',
    '/songs?q=%zz',
    '/songs?q=with space',
    '/' + 'x'.repeat(2048),
  ])('외부 주소·우회·API·로그인 루프·잘못된 경로를 차단한다: %s', (value) => {
    expect(safeReturnPath(value)).toBe('/library')
  })

  it('인증에는 로그인 화면만 전달하고 실제 경로는 탭 저장소로 유지한다', () => {
    const target = '/songs/123?q=a%26b#video'
    expect(beginLogin(target)).toBe('/login')
    const url = new URL(googleLoginUrl(), 'https://stelody.invalid')
    expect(url.pathname).toBe('/api/v1/auth/google')
    const callback = new URL(url.searchParams.get('returnTo')!, url.origin)
    expect(callback.pathname).toBe('/login')
    expect(callback.search).toBe('')
    expect(readLoginReturn()).toBe(target)
    expect(beginLogin('/login')).toBe('/login')
    expect(readLoginReturn()).toBe('/library')
  })

  it('유효한 긴 경로도 OAuth URL에 중첩하지 않고 사용 후 삭제한다', () => {
    const target = '/search?q=' + '%ED%95%9C'.repeat(150)
    rememberLoginReturn(target)
    expect(readLoginReturn()).toBe(target)
    expect(
      new URL(googleLoginUrl(), 'https://stelody.invalid').searchParams.get(
        'returnTo',
      ),
    ).toBe('/login')
    clearLoginReturn()
    expect(readLoginReturn()).toBe('/library')
  })

  it.each([
    'https://evil.example',
    '//evil.example',
    '/%2f%2fevil.example',
    '/api/v1/me',
    '/login',
  ])('저장소를 조작해도 이동 전에 차단한다: %s', (target) => {
    sessionStorage.setItem('stelody-login-return', target)
    expect(readLoginReturn()).toBe('/library')
  })

  it('저장소가 차단돼도 로그인은 가능하고 기본 경로로 복귀한다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(beginLogin('/account')).toBe('/login')
    expect(readLoginReturn()).toBe('/library')
    expect(() => clearLoginReturn()).not.toThrow()
  })
})
