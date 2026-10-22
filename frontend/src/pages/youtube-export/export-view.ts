import type { ExportJob } from '@/features/youtube-export/api'

export type ExportView =
  | 'prepare'
  | 'unconnected'
  | 'reconnect'
  | 'disabled'
  | 'disconnecting'
  | 'nosongs'
  | 'accepting'
  | 'accepted'
  | 'pending'
  | 'running'
  | 'loading'
  | 'queryerror'
  | 'retrying'
  | 'cancelling'
  | 'completed'
  | 'partial'
  | 'failed'
  | 'uncertain'
  | 'cancelled'
  | 'quota'
  | 'channel'
  | 'permission'
  | 'sourcechanged'

export function jobView(job: ExportJob): ExportView {
  if (job.status === 'SUCCEEDED')
    return job.skippedCount ? 'partial' : 'completed'
  if (job.status === 'QUEUED') return 'pending'
  if (job.status === 'RUNNING') return 'running'
  if (job.status === 'UNCERTAIN') return 'uncertain'
  if (job.status === 'CANCELLED') return 'cancelled'
  if (job.errorCode === 'YOUTUBE_QUOTA_EXCEEDED') return 'quota'
  if (job.errorCode === 'YOUTUBE_CHANNEL_REQUIRED') return 'channel'
  if (
    job.errorCode === 'YOUTUBE_RECONNECT_REQUIRED' ||
    job.errorCode === 'YOUTUBE_CONNECTION_REQUIRED'
  )
    return 'permission'
  return 'failed'
}
