import {
  queryOptions,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { endLocalSession, sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import {
  getWithdrawalConfirmation,
  startReauthentication,
  withdrawAccount,
} from './api'

export const accountKeys = {
  confirmation: (user: string) =>
    ['private', user, 'account', 'confirmation'] as const,
  write: (user: string) => ['private', user, 'account', 'write'] as const,
}

export const withdrawalConfirmationOptions = (user: string) =>
  queryOptions({
    queryKey: accountKeys.confirmation(user),
    queryFn: async ({ signal, client }) => {
      try {
        return await getWithdrawalConfirmation(signal)
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 401 &&
          client.getQueryData(sessionOptions.queryKey)?.id === user
        )
          await endLocalSession(client)
        throw error
      }
    },
    staleTime: 0,
    retry: false,
    refetchOnMount: 'always',
  })

export function useAccountActions(user: string) {
  const client = useQueryClient()
  const key = accountKeys.write(user)
  const mutation = useMutation({
    mutationKey: key,
    retry: false,
    gcTime: 0,
    mutationFn: async ({
      kind,
      signal,
      requestId,
    }: {
      kind: 'authorize' | 'withdraw'
      signal: AbortSignal
      requestId?: string | undefined
    }) => {
      if (client.getQueryData(sessionOptions.queryKey)?.id !== user)
        throw new Error('계정이 변경되었습니다.')
      return kind === 'authorize'
        ? startReauthentication(signal)
        : withdrawAccount(requestId ?? '', signal)
    },
  })
  return {
    async run(
      kind: 'authorize' | 'withdraw',
      signal: AbortSignal,
      requestId?: string,
    ) {
      if (client.isMutating({ mutationKey: key, exact: true }))
        return { accepted: false } as const
      const value = await mutation.mutateAsync({ kind, signal, requestId })
      return { accepted: true, value } as const
    },
  }
}
