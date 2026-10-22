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
  changeExport,
  createExport,
  exportPath,
  getExport,
  getActiveExport,
  isActiveExport,
  type ExportRequest,
} from './api'

export const exportKeys = {
  all: (user: string) => ['private', user, 'youtube-export'] as const,
  active: (user: string) => [...exportKeys.all(user), 'active'] as const,
  job: (user: string, playlist: string, job: string) =>
    [...exportKeys.all(user), 'job', playlist, job] as const,
  request: (user: string, playlist: string, request: string) =>
    [...exportKeys.all(user), 'request', playlist, request] as const,
  write: (user: string) => [...exportKeys.all(user), 'write'] as const,
}
const sameAccount = (client: ReturnType<typeof useQueryClient>, user: string) =>
  client.getQueryData(sessionOptions.queryKey)?.id === user
export const activeExportOptions = (user: string) =>
  queryOptions({
    queryKey: exportKeys.active(user),
    queryFn: async ({ signal, client }) => {
      try {
        return await getActiveExport(signal)
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
  })
export const exportJobOptions = (user: string, playlist: string, job: string) =>
  queryOptions({
    queryKey: exportKeys.job(user, playlist, job),
    queryFn: async ({ signal, client }) => {
      try {
        return await getExport(playlist, job, signal)
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
      !query.state.error &&
      ['QUEUED', 'RUNNING'].includes(query.state.data?.status ?? '')
        ? 2000
        : false,
  })
type Write =
  | { kind: 'create'; request: ExportRequest }
  | { kind: 'retry' | 'cancel'; jobId: string }

export function useExportActions(user: string, playlist: string) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const key = exportKeys.write(user)
  const pending = useIsMutating({ mutationKey: key, exact: true }) > 0
  const mutation = useMutation({
    mutationKey: key,
    retry: false,
    gcTime: 0,
    onMutate: async () => {
      await client.cancelQueries({ queryKey: exportKeys.all(user) })
    },
    mutationFn: async (input: Write) => {
      if (!sameAccount(client, user)) throw new Error('계정이 변경되었습니다.')
      return input.kind === 'create'
        ? createExport(playlist, input.request)
        : changeExport(playlist, input.jobId, input.kind)
    },
    onSuccess: (job, input) => {
      if (!sameAccount(client, user)) return
      client.setQueryData(exportKeys.job(user, playlist, job.id), job)
      client.setQueryData(exportKeys.active(user), {
        job: isActiveExport(job) ? job : null,
      })
      if (input.kind === 'create')
        client.setQueryData(
          exportKeys.request(user, playlist, input.request.requestId),
          job,
        )
    },
    onError: async (error) => {
      if (
        sameAccount(client, user) &&
        error instanceof ApiError &&
        error.status === 401
      ) {
        await endLocalSession(client)
        void navigate(loginPath(exportPath(playlist)))
      }
    },
  })
  return {
    pending,
    kind: mutation.isPending ? mutation.variables.kind : undefined,
    async run(input: Write) {
      if (client.isMutating({ mutationKey: key, exact: true })) return
      const job = await mutation.mutateAsync(input)
      return sameAccount(client, user) ? job : undefined
    },
  }
}
