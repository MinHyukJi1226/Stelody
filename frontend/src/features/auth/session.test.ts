import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { createQueryClient } from '@/app/query-client'
import { server } from '@/test/server'
import { ApiResponseError } from '@/shared/api/errors'
import { getSession } from './api'
import { endLocalSession, sessionOptions } from './session'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}

describe('세션 확인과 캐시', () => {
  it('같은 계정의 역할이나 상태가 변경되면 관리자 캐시만 제거한다', async () => {
    const client = createQueryClient()
    const activeAdmin = { ...user, role: 'ADMIN' }
    for (const next of [
      user,
      { ...user, role: 'ADMIN', status: 'SUSPENDED' },
    ]) {
      client.setQueryData(sessionOptions.queryKey, activeAdmin)
      client.setQueryData(['private', user.id, 'admin', 'inbox'], {
        secret: true,
      })
      client.setQueryData(['private', user.id, 'favorites'], ['mine'])
      server.use(http.get('*/api/v1/me', () => HttpResponse.json(next)))
      await expect(client.fetchQuery(sessionOptions)).resolves.toEqual(next)
      expect(
        client.getQueriesData({ queryKey: ['private', user.id, 'admin'] }),
      ).toEqual([])
      expect(client.getQueryData(['private', user.id, 'favorites'])).toEqual([
        'mine',
      ])
    }
    client.clear()
  })
  it('계정 전환과 만료를 관측하면 이전 개인 캐시를 지운 뒤 세션을 갱신한다', async () => {
    const client = createQueryClient()
    const second = { ...user, id: '00000000-0000-4000-8000-000000000002' }
    client.setQueryData(sessionOptions.queryKey, user)
    client.setQueryData(['private', user.id, 'favorites'], ['old-account'])
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(second)))
    await expect(client.fetchQuery(sessionOptions)).resolves.toEqual(second)
    expect(client.getQueriesData({ queryKey: ['private'] })).toEqual([])
    client.setQueryData(['private', second.id, 'favorites'], ['new-account'])
    server.use(
      http.get('*/api/v1/me', () =>
        HttpResponse.json({ code: 'SESSION_EXPIRED' }, { status: 401 }),
      ),
    )
    await expect(client.fetchQuery(sessionOptions)).resolves.toBeNull()
    expect(client.getQueriesData({ queryKey: ['private'] })).toEqual([])
    client.clear()
  })
  it('401만 비로그인으로 취급하고 장애와 잘못된 성공 응답을 구분한다', async () => {
    server.use(
      http.get('*/api/v1/me', () =>
        HttpResponse.json({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401 }),
      ),
    )
    await expect(getSession()).resolves.toBeNull()
    server.use(
      http.get('*/api/v1/me', () =>
        HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 }),
      ),
    )
    await expect(getSession()).rejects.toMatchObject({ status: 503 })
    server.use(
      http.get('*/api/v1/me', () => HttpResponse.json({ id: 'broken' })),
    )
    await expect(getSession()).rejects.toBeInstanceOf(ApiResponseError)
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(user)))
    await expect(getSession()).resolves.toEqual(user)
  })

  it('로그아웃은 진행 중인 개인·세션 조회를 취소하고 공개 캐시는 유지한다', async () => {
    const client = createQueryClient()
    client.setQueryData(['private', user.id, 'favorites'], ['secret'])
    client.setQueryData(['songs', 'latest'], ['public'])
    client.setQueryData(sessionOptions.queryKey, user)
    server.use(
      http.get('*/api/v1/me', async () => {
        await delay(100)
        return HttpResponse.json(user)
      }),
    )
    let cancelled = false
    const pendingPrivate = client
      .fetchQuery({
        queryKey: ['private', user.id, 'playlists'],
        queryFn: ({ signal }) =>
          new Promise((resolve) => {
            signal.addEventListener('abort', () => {
              cancelled = true
              resolve(['late-secret'])
            })
          }),
      })
      .catch(() => undefined)
    const pendingSession = client
      .fetchQuery(sessionOptions)
      .catch(() => undefined)
    await endLocalSession(client)
    await Promise.all([pendingPrivate, pendingSession])
    expect(cancelled).toBe(true)
    expect(client.getQueriesData({ queryKey: ['private'] })).toEqual([])
    expect(client.getQueryData(sessionOptions.queryKey)).toBeNull()
    expect(client.getQueryData(['songs', 'latest'])).toEqual(['public'])
    client.clear()
  })
})
