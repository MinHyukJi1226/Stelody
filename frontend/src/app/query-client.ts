import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/errors'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (count, error) =>
          count < 1 && !(error instanceof ApiError && error.status < 500),
      },
      mutations: { retry: false },
    },
  })
}
