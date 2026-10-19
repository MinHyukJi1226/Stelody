import { afterEach, describe, expect, it, vi } from 'vitest'
import { loginPath, oauthReturnPath, safeReturnPath } from './return-path'
import { googleLoginUrl } from './api'

describe('로그인 복귀 경로', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('로컬 개발에서만 loginClient를 지정하고 운영 요청에는 생략한다', () => {
    vi.stubEnv('DEV', true)
    const local = new URL(googleLoginUrl('/library'), 'http://localhost:5173')
    expect(local.searchParams.get('loginClient')).toBe('local')
    vi.stubEnv('DEV', false)
    const production = new URL(
      googleLoginUrl('/library'),
      'https://stelody.example',
    )
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

  it('성공·실패 모두 로그인 화면으로 복귀하며 원래 경로를 중첩 인코딩한다', () => {
    const target = '/songs/123?q=a%26b#video'
    const url = new URL(googleLoginUrl(target), 'https://stelody.invalid')
    expect(url.pathname).toBe('/api/v1/auth/google')
    const callback = new URL(url.searchParams.get('returnTo')!, url.origin)
    expect(callback.pathname).toBe('/login')
    expect(callback.searchParams.get('returnTo')).toBe(target)
    expect(loginPath('/login')).toBe('/login?returnTo=%2Flibrary')
  })

  it('중첩 인코딩으로 복귀 주소 제한을 넘으면 보관함으로 복귀한다', () => {
    expect(oauthReturnPath('/search?q=' + '%ED%95%9C'.repeat(150))).toBe(
      loginPath('/library'),
    )
  })
})
