import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import type { ApiSchemas } from '@/shared/api/types'

export type ExportJob = ApiSchemas['com.stelody.export.dto.ExportDtos.Job']
export type ExportRequest =
  ApiSchemas['com.stelody.export.dto.ExportDtos.Create']
export type ActiveExport =
  ApiSchemas['com.stelody.export.dto.ExportDtos.ActiveJob']
export const isActiveExport = (job: ExportJob) =>
  ['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'].includes(job.status)
const count = z.number().int().nonnegative().max(500)
const jobSchema = z
  .object({
    id: z.uuid(),
    playlistId: z.uuid(),
    version: z.number().int().nonnegative(),
    status: z.enum([
      'QUEUED',
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'UNCERTAIN',
      'CANCELLED',
    ]),
    totalCount: count,
    copiedCount: count,
    skippedCount: count,
    remainingCount: count,
    youtubePlaylistId: z.string().min(1).nullable(),
    youtubeUrl: z.url().nullable(),
    errorCode: z.string().nullable(),
    createdAt: z.iso.datetime({ offset: true }),
    updatedAt: z.iso.datetime({ offset: true }),
  })
  .refine(
    (j) => j.totalCount === j.copiedCount + j.skippedCount + j.remainingCount,
  ) satisfies z.ZodType<ExportJob>

export const exportPath = (playlistId: string, jobId?: string) =>
  `/library/playlists/${encodeURIComponent(playlistId)}/youtube-export${jobId ? `?jobId=${encodeURIComponent(jobId)}` : ''}`

function parseJob(value: unknown, playlistId?: string, jobId?: string) {
  const parsed = jobSchema.safeParse(value)
  if (
    !parsed.success ||
    (playlistId && parsed.data.playlistId !== playlistId) ||
    (jobId && parsed.data.id !== jobId)
  )
    throw new ApiResponseError()
  if (parsed.data.youtubeUrl) {
    const url = new URL(parsed.data.youtubeUrl)
    if (
      url.origin !== 'https://www.youtube.com' ||
      url.pathname !== '/playlist' ||
      url.username ||
      url.password ||
      url.hash ||
      !parsed.data.youtubePlaylistId ||
      url.searchParams.getAll('list').length !== 1 ||
      url.searchParams.get('list') !== parsed.data.youtubePlaylistId
    )
      throw new ApiResponseError()
  }
  return parsed.data
}

export async function getActiveExport(
  signal?: AbortSignal,
): Promise<ActiveExport> {
  const parsed = z
    .object({ job: jobSchema.nullable() })
    .safeParse(
      await apiClient.request<unknown>(
        '/me/youtube-exports/active',
        signal ? { signal } : {},
      ),
    )
  if (!parsed.success) throw new ApiResponseError()
  if (!parsed.data.job) return { job: null }
  const job = parseJob(parsed.data.job)
  if (!isActiveExport(job)) throw new ApiResponseError()
  return { job }
}

export async function createExport(playlistId: string, input: ExportRequest) {
  const body = z
    .object({ requestId: z.uuid(), version: z.number().int().nonnegative() })
    .parse(input)
  const job = parseJob(
    await apiClient.request<unknown>(
      `/me/playlists/${encodeURIComponent(playlistId)}/youtube-exports`,
      { method: 'POST', body },
    ),
    playlistId,
  )
  if (job.version !== input.version) throw new ApiResponseError()
  return job
}

export async function getExport(
  playlistId: string,
  jobId: string,
  signal?: AbortSignal,
) {
  return parseJob(
    await apiClient.request<unknown>(
      `/me/youtube-exports/${encodeURIComponent(jobId)}`,
      signal ? { signal } : {},
    ),
    playlistId,
    jobId,
  )
}

export async function changeExport(
  playlistId: string,
  jobId: string,
  action: 'retry' | 'cancel',
) {
  return parseJob(
    await apiClient.request<unknown>(
      `/me/youtube-exports/${encodeURIComponent(jobId)}/${action}`,
      { method: 'POST' },
    ),
    playlistId,
    jobId,
  )
}
