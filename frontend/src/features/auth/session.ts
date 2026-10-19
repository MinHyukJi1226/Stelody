import { queryOptions, type QueryClient } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import type { CurrentUser } from '@/shared/api/types'
import { getSession } from './api'

const sessionKey = ['auth', 'session'] as const

export const sessionOptions = queryOptions({
  queryKey: sessionKey,
  queryFn: async ({ signal, client }) => {
    const session = await getSession(signal)
    const previous = client.getQueryData<CurrentUser | null>(sessionKey)
    if (previous === undefined || previous?.id !== session?.id) {
      // Clear the old account before publishing the next identity to observers.
      await clearPrivateQueries(client)
      signal.throwIfAborted()
      apiClient.invalidateCsrf()
    }
    return session
  },
  staleTime: 0,
  retry: false,
})

// Personal features must use ['private', userId, ...] query keys.
export async function clearPrivateQueries(client: QueryClient) {
  await client.cancelQueries({ queryKey: ['private'] })
  client.removeQueries({ queryKey: ['private'] })
}

export async function endLocalSession(client: QueryClient) {
  await client.cancelQueries({ queryKey: sessionOptions.queryKey })
  await clearPrivateQueries(client)
  apiClient.invalidateCsrf()
  client.setQueryData(sessionOptions.queryKey, null)
}
