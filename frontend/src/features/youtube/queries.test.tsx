import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router'
import { http, HttpResponse } from 'msw'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions } from '@/features/auth/session'
import { apiClient } from '@/shared/api/client'
import { server } from '@/test/server'
import { useYouTubeActions } from './queries'

const user = {
  id: '00000000-0000-4000-8000-000000000800',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const exportPath =
  '/library/playlists/00000000-0000-4000-8000-000000000900/youtube-export?jobId=00000000-0000-4000-8000-000000000901'
const manager = `/library/youtube?${new URLSearchParams({ returnTo: exportPath })}`
const client = createQueryClient()
beforeEach(() => {
  client.setQueryData(sessionOptions.queryKey, user)
  apiClient.invalidateCsrf()
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'test' }),
    ),
  )
})
afterEach(() => client.clear())

for (const kind of ['authorize', 'disconnect'] as const)
  for (const returnTo of ['/library/youtube', manager])
    it(`${kind} 401 로그인 복귀에 전달한 연결 관리 경로를 유지한다: ${returnTo}`, async () => {
      let writes = 0
      const expired = () => {
        writes++
        return HttpResponse.json({ code: 'SESSION_EXPIRED' }, { status: 401 })
      }
      server.use(
        http.post('*/api/v1/me/youtube/authorizations', expired),
        http.delete('*/api/v1/me/youtube/connection', expired),
      )
      client.setQueryData(['private', user.id, 'youtube-export', 'job'], {
        id: 'private-job',
      })
      const { result } = renderHook(
        () => ({
          action: useYouTubeActions(user.id, returnTo),
          location: useLocation(),
        }),
        {
          wrapper: ({ children }: { children: ReactNode }) => (
            <QueryClientProvider client={client}>
              <MemoryRouter initialEntries={[returnTo]}>
                {children}
              </MemoryRouter>
            </QueryClientProvider>
          ),
        },
      )
      await act(async () => {
        await expect(result.current.action.run(kind)).rejects.toMatchObject({
          status: 401,
        })
      })
      expect(result.current.location.pathname).toBe('/login')
      expect(
        new URLSearchParams(result.current.location.search).get('returnTo'),
      ).toBe(returnTo)
      expect(client.getQueryData(sessionOptions.queryKey)).toBeNull()
      expect(client.getQueriesData({ queryKey: ['private'] })).toHaveLength(0)
      expect(writes).toBe(1)
    })
