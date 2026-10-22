import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Link,
  useNavigate,
  useNavigation,
  useParams,
  useSearchParams,
} from 'react-router'
import { z } from 'zod'
import { sessionOptions } from '@/features/auth/session'
import { playlistDetailOptions } from '@/features/playlists/queries'
import { youtubeConnectionOptions } from '@/features/youtube/queries'
import {
  exportPath,
  type ExportJob,
  type ExportRequest,
} from '@/features/youtube-export/api'
import {
  activeExportOptions,
  exportJobOptions,
  exportKeys,
  useExportActions,
} from '@/features/youtube-export/queries'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { PageState } from '@/shared/ui/page-state'
import { SiteFooter } from '@/shared/ui/site-footer'
import { ExportPanel } from './components/export-panel'
import { jobView, type ExportView } from './export-view'

function readContext(params: URLSearchParams) {
  const jobId = params.get('jobId'),
    requestId = params.get('requestId'),
    version = params.get('version')
  const invalid =
    ['jobId', 'requestId', 'version'].some(
      (k) => params.getAll(k).length > 1,
    ) ||
    (!!jobId && (!!requestId || version !== null)) ||
    (params.has('jobId') && !z.uuid().safeParse(jobId).success) ||
    (params.has('requestId') &&
      (!z.uuid().safeParse(requestId).success ||
        version === null ||
        !/^\d+$/.test(version) ||
        !Number.isSafeInteger(Number(version)))) ||
    (version !== null && !requestId)
  return {
    invalid,
    jobId,
    request:
      !invalid && requestId && version !== null
        ? { requestId, version: Number(version) }
        : undefined,
  }
}
function writeMessage(error: unknown) {
  if (error instanceof ApiError)
    switch (error.code) {
      case 'EXPORT_ALREADY_ACTIVE':
        return '이미 진행 중인 내보내기 작업이 있습니다.'
      case 'EXPORT_REQUEST_REUSED':
        return '접수 정보가 일치하지 않습니다. 기존 작업을 확인해 주세요.'
      case 'EXPORT_ITEM_LIMIT':
        return '내보내기는 한 번에 500곡까지 가능합니다.'
      case 'YOUTUBE_EXPORT_DISABLED':
        return '지금은 내보내기를 사용할 수 없어요.'
      case 'YOUTUBE_CONNECTION_REQUIRED':
        return 'YouTube 연결 상태를 확인해 주세요.'
      case 'YOUTUBE_REVOCATION_PENDING':
        return 'YouTube 연결 해제가 끝날 때까지 기다려 주세요.'
      case 'EXPORT_NOT_RETRYABLE':
        return '취소된 작업은 다시 시도할 수 없습니다.'
    }
  return '요청 결과를 확인하지 못했어요. 상태를 다시 확인해 주세요.'
}

export function YouTubeExportPage() {
  const { playlistId = '' } = useParams()
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  return session.data ? (
    <ExportScreen
      key={`${session.data.id}:${playlistId}`}
      user={session.data.id}
      playlistId={playlistId}
    />
  ) : null
}
function ExportScreen({
  user,
  playlistId,
}: {
  user: string
  playlistId: string
}) {
  const [params] = useSearchParams()
  const context = readContext(params)
  const valid = z.uuid().safeParse(playlistId).success && !context.invalid
  const client = useQueryClient()
  const navigate = useNavigate()
  const navigation = useNavigation()
  const leaving = useRef(false)
  useLayoutEffect(() => {
    leaving.current =
      !!navigation.location &&
      navigation.location.pathname !== exportPath(playlistId)
  }, [navigation.location, playlistId])
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const [error, setError] = useState<string>()
  const [sourceChanged, setSourceChanged] = useState(false)
  const [noSongs, setNoSongs] = useState(false)
  const [blocked, setBlocked] = useState(false)
  const [recovering, setRecovering] = useState(false)
  const [operation, setOperation] = useState<'create' | 'retry' | 'cancel'>()
  const [acceptedId, setAcceptedId] = useState<string>()
  const source = useQuery({
    ...playlistDetailOptions(user, playlistId),
    enabled: valid,
  })
  const connection = useQuery({
    ...youtubeConnectionOptions(user),
    enabled: valid,
  })
  const confirmedRequest = context.request
    ? client.getQueryData<ExportJob>(
        exportKeys.request(user, playlistId, context.request.requestId),
      )
    : undefined
  const jobId = context.jobId ?? confirmedRequest?.id
  const action = useExportActions(user, playlistId)
  const active = useQuery({
    ...activeExportOptions(user),
    enabled: valid && !jobId && !action.pending,
  })
  const query = useQuery({
    ...exportJobOptions(user, playlistId, jobId ?? ''),
    enabled: valid && !!jobId,
  })
  const job = query.data ?? confirmedRequest
  const playlist = source.data
  const denied =
    !valid ||
    [source.error, query.error, !jobId ? active.error : null].some(
      (e) => e instanceof ApiError && [403, 404].includes(e.status),
    )
  const busy =
    action.pending ||
    recovering ||
    source.isFetching ||
    connection.isFetching ||
    (!jobId && active.isFetching)
  const live = () =>
    mounted.current &&
    !leaving.current &&
    client.getQueryData(sessionOptions.queryKey)?.id === user
  useEffect(() => {
    if (context.request && confirmedRequest && navigation.state === 'idle')
      void navigate(exportPath(playlistId, confirmedRequest.id), {
        replace: true,
      })
  }, [
    context.request,
    confirmedRequest,
    playlistId,
    navigate,
    navigation.state,
  ])
  const activeJob = active.data?.job
  useEffect(() => {
    if (
      !jobId &&
      activeJob &&
      !active.isError &&
      !active.isFetching &&
      !recovering &&
      navigation.state === 'idle'
    ) {
      client.setQueryData(
        exportKeys.job(user, activeJob.playlistId, activeJob.id),
        activeJob,
      )
      void navigate(exportPath(activeJob.playlistId, activeJob.id), {
        replace: true,
      })
    }
  }, [
    activeJob,
    jobId,
    active.isError,
    active.isFetching,
    recovering,
    navigation.state,
    client,
    user,
    navigate,
  ])

  async function recover(clearRejectedRequest = false) {
    if (!live()) return
    setRecovering(true)
    try {
      const result = await active.refetch()
      if (!live() || result.error || !result.data) return
      if (result.data.job === null && clearRejectedRequest) {
        setBlocked(false)
        setError(undefined)
        void navigate(exportPath(playlistId), { replace: true })
      }
    } finally {
      if (live()) setRecovering(false)
    }
  }

  async function start() {
    if (
      !live() ||
      !playlist ||
      busy ||
      jobId ||
      blocked ||
      sourceChanged ||
      noSongs ||
      source.isError ||
      connection.isError ||
      active.isError ||
      active.data?.job !== null ||
      connection.data?.status !== 'CONNECTED' ||
      playlist.availableCount === 0
    )
      return
    const input: ExportRequest = context.request ?? {
      requestId: crypto.randomUUID(),
      version: playlist.version,
    }
    setError(undefined)
    setOperation('create')
    if (!context.request)
      await navigate(
        `${exportPath(playlistId)}?${new URLSearchParams({ requestId: input.requestId, version: String(input.version) })}`,
        { replace: true },
      )
    if (!live()) return
    try {
      const result = await action.run({ kind: 'create', request: input })
      if (result && live()) {
        setAcceptedId(result.id)
      }
    } catch (failure) {
      if (!live()) return
      if (failure instanceof ApiError && failure.code === 'PLAYLIST_CHANGED') {
        setSourceChanged(true)
        void navigate(exportPath(playlistId), { replace: true })
      } else if (
        failure instanceof ApiError &&
        failure.code === 'NO_EXPORTABLE_SONGS'
      ) {
        setNoSongs(true)
        void navigate(exportPath(playlistId), { replace: true })
      } else {
        setError(writeMessage(failure))
        if (
          failure instanceof ApiError &&
          ['EXPORT_ALREADY_ACTIVE', 'EXPORT_REQUEST_REUSED'].includes(
            failure.code,
          )
        ) {
          setBlocked(true)
          await recover(true)
        } else if (!(failure instanceof ApiError) || failure.status >= 500) {
          await recover()
        }
        if (
          failure instanceof ApiError &&
          [
            'YOUTUBE_CONNECTION_REQUIRED',
            'YOUTUBE_REVOCATION_PENDING',
            'YOUTUBE_EXPORT_DISABLED',
          ].includes(failure.code)
        ) {
          void connection.refetch()
          void navigate(exportPath(playlistId), { replace: true })
        }
      }
    } finally {
      if (live()) setOperation(undefined)
    }
  }
  async function change(kind: 'retry' | 'cancel') {
    if (
      !live() ||
      !job ||
      busy ||
      (kind === 'retry' &&
        (query.isError ||
          !['FAILED', 'UNCERTAIN'].includes(job.status) ||
          connection.data?.status !== 'CONNECTED')) ||
      (kind === 'cancel' &&
        !['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'].includes(job.status))
    )
      return
    setError(undefined)
    setOperation(kind)
    try {
      await action.run({ kind, jobId: job.id })
    } catch (failure) {
      if (!live()) return
      setError(writeMessage(failure))
      void query.refetch()
      void connection.refetch()
    } finally {
      if (live()) setOperation(undefined)
    }
  }
  async function reload() {
    if (busy) return
    const result = await source.refetch()
    if (!live()) return
    if (result.error) {
      setError(writeMessage(result.error))
      return
    }
    setSourceChanged(false)
    setNoSongs(false)
    setError(undefined)
    void navigate(exportPath(playlistId), { replace: true })
  }
  const preflight: ExportView = sourceChanged
    ? 'sourcechanged'
    : noSongs || playlist?.availableCount === 0
      ? 'nosongs'
      : connection.data?.status === 'DISABLED'
        ? 'disabled'
        : connection.data?.status === 'REVOKING'
          ? 'disconnecting'
          : connection.data?.status === 'RECONNECT_REQUIRED'
            ? 'reconnect'
            : connection.data?.status === 'CONNECTED'
              ? 'prepare'
              : 'unconnected'
  const view: ExportView = action.pending
    ? operation === 'create'
      ? 'accepting'
      : operation === 'retry'
        ? 'retrying'
        : operation === 'cancel'
          ? 'cancelling'
          : 'loading'
    : recovering || (!jobId && active.isPending)
      ? 'loading'
      : (jobId && query.isError) ||
          (!jobId &&
            (source.isError ||
              connection.isError ||
              active.isError ||
              blocked ||
              !!context.request))
        ? 'queryerror'
        : !playlist ||
            (jobId && !job) ||
            (!jobId && (connection.isPending || !!activeJob))
          ? 'loading'
          : job
            ? acceptedId === job.id &&
              job.status === 'QUEUED' &&
              query.isFetching
              ? 'accepted'
              : jobView(job)
            : preflight
  const canCancel =
    !!job && ['QUEUED', 'RUNNING', 'FAILED', 'UNCERTAIN'].includes(job.status)
  const canRetry =
    !!job &&
    ['FAILED', 'UNCERTAIN'].includes(job.status) &&
    !query.isError &&
    connection.data?.status === 'CONNECTED' &&
    !connection.isError
  return (
    <div className="favorites-page flex min-w-0 flex-col gap-12">
      <section className="flex min-w-0 flex-1 flex-col items-center gap-6">
        <h1 className="w-full text-[32px] font-bold leading-[1.5]">
          YouTube로 내보내기
        </h1>
        {denied ? (
          <PageState
            kind="unavailable"
            title="내보내기 정보를 확인할 수 없습니다"
            description="목록이나 작업이 없거나 현재 계정에서 볼 수 없습니다."
            action={{ to: '/library/playlists', label: '내 플레이리스트' }}
          />
        ) : (
          <>
            <ExportPanel
              view={view}
              name={playlist?.name}
              total={playlist?.totalCount}
              available={playlist?.availableCount}
              job={job}
              busy={busy}
              connectionPath={`/library/youtube?${new URLSearchParams({
                returnTo: jobId
                  ? exportPath(playlistId, jobId)
                  : `${exportPath(playlistId)}${params.size ? `?${params}` : ''}`,
              })}`}
              canRetry={canRetry}
              canCancel={canCancel}
              notice={
                error ? (
                  <p role="alert" className="rounded-lg bg-rose p-3 text-error">
                    {error}
                  </p>
                ) : undefined
              }
              onStart={() => void start()}
              onRetry={() => void change('retry')}
              onCancel={() => void change('cancel')}
              onReload={() => void reload()}
              onRead={() => {
                if (jobId) void query.refetch()
                else if (blocked || active.isError) void recover(blocked)
                else if (context.request) void start()
                else {
                  void source.refetch()
                  void connection.refetch()
                  void active.refetch()
                }
              }}
            />
            <Button asChild>
              <Link to={`/library/playlists/${playlistId}`}>
                플레이리스트로 돌아가기
              </Link>
            </Button>
          </>
        )}
      </section>
      <SiteFooter />
    </div>
  )
}
