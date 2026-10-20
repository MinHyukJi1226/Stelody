import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { createQueryClient } from '@/app/query-client'
import { sessionOptions } from '@/features/auth/session'
import { apiClient } from '@/shared/api/client'
import { ToastProvider } from '@/shared/ui/toast-provider'
import { server } from '@/test/server'
import { FavoriteButton } from './favorite-button'
import { useFavoriteChange } from './use-favorite-change'

const user = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'test@example.com',
  role: 'USER',
  status: 'ACTIVE',
}
const songId = '00000000-0000-4000-8000-000000000010'

it('로그인 재개 요청 도중 카드가 늦게 마운트되어도 이전 저장 상태를 조회하지 않는다', async () => {
  const client = createQueryClient()
  client.setQueryData(sessionOptions.queryKey, user)
  apiClient.invalidateCsrf()
  let reads = 0
  let writes = 0
  let complete: () => void = () => {}
  const gate = new Promise<void>((resolve) => {
    complete = resolve
  })
  server.use(
    http.get('*/api/v1/auth/csrf', () =>
      HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'test' }),
    ),
    http.put('*/api/v1/me/favorites/:id', async () => {
      writes++
      await gate
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('*/api/v1/me/favorites/:id', () => {
      reads++
      return HttpResponse.json({ songId, favorited: false })
    }),
  )
  function StartSave() {
    const { change } = useFavoriteChange(songId)
    return <button onClick={() => change(true)}>재개</button>
  }
  const content = (show: boolean) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ToastProvider>
          <StartSave />
          {show && <FavoriteButton songId={songId} songTitle="노래" />}
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>
  )
  const view = render(content(false))
  try {
    await userEvent.click(screen.getByRole('button', { name: '재개' }))
    await waitFor(() => expect(writes).toBe(1))
    view.rerender(content(true))
    expect(
      screen.getByRole('button', { name: '노래 즐겨찾기 확인 중' }),
    ).toBeDisabled()
    await act(async () => {
      complete()
      await gate
    })
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: '노래 즐겨찾기 해제' }),
      ).toHaveAttribute('aria-pressed', 'true'),
    )
    expect(reads).toBe(0)
  } finally {
    complete()
    view.unmount()
    client.clear()
  }
})

it('상태 조회 실패의 다시 확인은 저장 요청 없이 상태만 재조회한다', async () => {
  const client = createQueryClient()
  client.setQueryData(sessionOptions.queryKey, user)
  let failed = true
  let writes = 0
  server.use(
    http.get('*/api/v1/me/favorites/:id', () =>
      failed
        ? HttpResponse.json({ code: 'ACCESS_DENIED' }, { status: 403 })
        : HttpResponse.json({ songId, favorited: false }),
    ),
    http.put('*/api/v1/me/favorites/:id', () => {
      writes++
      return new HttpResponse(null, { status: 204 })
    }),
  )
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ToastProvider>
          <FavoriteButton songId={songId} songTitle="노래" />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  try {
    const retry = await screen.findByText('다시 확인')
    failed = false
    await userEvent.click(retry)
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: '노래 즐겨찾기 저장' }),
      ).toBeEnabled(),
    )
    expect(writes).toBe(0)
    expect(screen.queryByText('다시 확인')).not.toBeInTheDocument()
  } finally {
    view.unmount()
    client.clear()
  }
})
