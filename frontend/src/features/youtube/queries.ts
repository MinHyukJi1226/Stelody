import {
  queryOptions,
  useIsMutating,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { endLocalSession, sessionOptions } from '@/features/auth/session'
import { loginPath } from '@/features/auth/return-path'
import { ApiError } from '@/shared/api/errors'
import {
  authorizeYouTube,
  disconnectYouTube,
  getYouTubeConnection,
  youtubePath,
} from './api'

export const youtubeKeys = {
  connection: (user: string) =>
    ['private', user, 'youtube', 'connection'] as const,
  write: (user: string) => ['private', user, 'youtube', 'write'] as const,
}
const sameAccount = (client: ReturnType<typeof useQueryClient>, user: string) =>
  client.getQueryData(sessionOptions.queryKey)?.id === user

export const youtubeConnectionOptions = (user: string) =>
  queryOptions({
    queryKey: youtubeKeys.connection(user),
    queryFn: async ({ signal, client }) => {
      try {
        return await getYouTubeConnection(signal)
      } catch (error) {
        if (
          sameAccount(client, user) &&
          error instanceof ApiError &&
          error.status === 401
        )
          await endLocalSession(client)
        throw error
      }
    },
    staleTime: 0,
    refetchOnMount: 'always',
    refetchInterval: (query) =>
      !query.state.error && query.state.data?.status === 'REVOKING'
        ? 5000
        : false,
  })

export function useYouTubeActions(user: string) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const key = youtubeKeys.write(user)
  const pending = useIsMutating({ mutationKey: key, exact: true }) > 0
  const mutation = useMutation({
    mutationKey: key,
    retry: false,
    gcTime: 0,
    onMutate: async () => {
      await client.cancelQueries({ queryKey: youtubeKeys.connection(user) })
    },
    mutationFn: async (kind: 'authorize' | 'disconnect') => {
      if (!sameAccount(client, user)) throw new Error('계정이 변경되었습니다.')
      return kind === 'authorize'
        ? { kind, value: await authorizeYouTube() }
        : { kind, value: await disconnectYouTube() }
    },
    onSuccess: (result) => {
      if (sameAccount(client, user) && result.kind === 'disconnect')
        client.setQueryData(youtubeKeys.connection(user), result.value)
    },
    onError: async (error) => {
      if (!sameAccount(client, user)) return
      if (error instanceof ApiError && error.status === 401) {
        await endLocalSession(client)
        void navigate(loginPath(youtubePath))
      }
    },
  })
  return {
    pending,
    async run(kind: 'authorize' | 'disconnect') {
      if (!user || client.isMutating({ mutationKey: key, exact: true })) return
      const result = await mutation.mutateAsync(kind)
      return sameAccount(client, user) ? result : undefined
    },
  }
}
