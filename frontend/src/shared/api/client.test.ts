import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { createApiClient } from './client'
import { ApiError, ApiResponseError } from './errors'

const base = 'http://localhost/api/v1'

describe('공통 API 클라이언트', () => {
  it('공개 GET은 CSRF 요청 없이 쿠키와 취소 신호를 전달한다', async () => {
    server.use(
      http.get(`${base}/songs`, ({ request }) => {
        expect(request.credentials).toBe('include')
        expect(request.headers.has('X-CSRF-TOKEN')).toBe(false)
        return HttpResponse.json({ items: [], totalCount: 0 })
      }),
    )
    await expect(createApiClient(base).request('/songs')).resolves.toEqual({
      items: [],
      totalCount: 0,
    })
  })

  it('동시 변경 요청은 CSRF를 한 번 조회하고 헤더를 붙이며 204를 처리한다', async () => {
    let csrfCalls = 0
    server.use(
      http.get(`${base}/auth/csrf`, async () => {
        csrfCalls++
        await delay(10)
        return HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'first' })
      }),
      http.put(`${base}/me/favorites/:id`, ({ request }) => {
        expect(request.headers.get('X-CSRF-TOKEN')).toBe('first')
        expect(request.credentials).toBe('include')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const client = createApiClient(base)
    await expect(
      Promise.all(
        ['a', 'b'].map((id) =>
          client.request(`/me/favorites/${id}`, { method: 'PUT' }),
        ),
      ),
    ).resolves.toEqual([undefined, undefined])
    expect(csrfCalls).toBe(1)
  })

  it('인증 전환으로 무효화하면 다음 변경 요청은 새 CSRF를 사용한다', async () => {
    let calls = 0
    const tokens: (string | null)[] = []
    server.use(
      http.get(`${base}/auth/csrf`, () =>
        HttpResponse.json({
          headerName: 'X-CSRF-TOKEN',
          token: `token-${++calls}`,
        }),
      ),
      http.post(`${base}/auth/logout`, ({ request }) => {
        tokens.push(request.headers.get('X-CSRF-TOKEN'))
        return new HttpResponse(null, { status: 204 })
      }),
      http.put(`${base}/me/favorites/a`, ({ request }) => {
        tokens.push(request.headers.get('X-CSRF-TOKEN'))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const client = createApiClient(base)
    await client.request('/auth/logout', { method: 'POST' })
    await client.request('/me/favorites/a', { method: 'PUT' })
    client.invalidateCsrf()
    await client.request('/me/favorites/a', { method: 'PUT' })
    expect(tokens).toEqual(['token-1', 'token-2', 'token-3'])
  })

  it('실패한 CSRF 조회는 저장 요청을 보내지 않고 다음 시도에서 재조회한다', async () => {
    let calls = 0
    let saves = 0
    server.use(
      http.get(`${base}/auth/csrf`, () =>
        ++calls === 1
          ? HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 })
          : HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'new' }),
      ),
      http.put(`${base}/me/favorites/a`, () => {
        saves++
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const client = createApiClient(base)
    await expect(
      client.request('/me/favorites/a', { method: 'PUT' }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(saves).toBe(0)
    await client.request('/me/favorites/a', { method: 'PUT' })
    expect(calls).toBe(2)
    expect(saves).toBe(1)
  })

  it('409 충돌과 배열 필드 오류·traceId를 보존하고 자동 재전송하지 않는다', async () => {
    let calls = 0
    const problem = {
      code: 'PLAYLIST_CHANGED',
      title: '목록이 변경되었습니다',
      traceId: 'trace',
      fieldErrors: [
        {
          field: 'links[0].url',
          code: 'INVALID_FORMAT',
          message: '주소를 확인해 주세요',
        },
      ],
    }
    server.use(
      http.get(`${base}/songs`, () => {
        calls++
        return HttpResponse.json(problem, { status: 409 })
      }),
    )
    await expect(createApiClient(base).request('/songs')).rejects.toMatchObject(
      {
        status: 409,
        code: problem.code,
        message: problem.title,
        traceId: problem.traceId,
        fieldErrors: problem.fieldErrors,
      },
    )
    expect(calls).toBe(1)
  })

  it('인증 실패 후 새 토큰을 조회하되 실패한 변경 요청은 재시도하지 않는다', async () => {
    let tokens = 0
    let saves = 0
    server.use(
      http.get(`${base}/auth/csrf`, () =>
        HttpResponse.json({
          headerName: 'X-CSRF-TOKEN',
          token: `t${++tokens}`,
        }),
      ),
      http.put(`${base}/me/favorites/a`, () => {
        saves++
        return HttpResponse.json({ code: 'ACCESS_DENIED' }, { status: 403 })
      }),
    )
    const client = createApiClient(base)
    await expect(
      client.request('/me/favorites/a', { method: 'PUT' }),
    ).rejects.toMatchObject({ status: 403 })
    expect(saves).toBe(1)
    await expect(
      client.request('/me/favorites/a', { method: 'PUT' }),
    ).rejects.toMatchObject({ status: 403 })
    expect(tokens).toBe(2)
  })

  it('HTML 응답은 성공 DTO로 취급하지 않고 서버 오류 원문도 노출하지 않는다', async () => {
    server.use(
      http.get(
        `${base}/songs`,
        () =>
          new HttpResponse('<html>internal secret</html>', {
            headers: { 'Content-Type': 'text/html' },
          }),
      ),
    )
    await expect(
      createApiClient(base).request('/songs'),
    ).rejects.toBeInstanceOf(ApiResponseError)
    server.use(
      http.get(
        `${base}/songs`,
        () => new HttpResponse('internal secret', { status: 500 }),
      ),
    )
    await expect(createApiClient(base).request('/songs')).rejects.toMatchObject(
      { code: 'UNKNOWN_ERROR', status: 500 },
    )
  })

  it('이전 검색 요청을 AbortSignal로 취소할 수 있다', async () => {
    server.use(
      http.get(`${base}/songs`, async () => {
        await delay(100)
        return HttpResponse.json({ items: [] })
      }),
    )
    const controller = new AbortController()
    const request = createApiClient(base).request('/songs', {
      signal: controller.signal,
    })
    controller.abort()
    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
  })
})
