import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { z } from 'zod'
import { sessionOptions } from '@/features/auth/session'
import { playlistNameSchema, type Playlist } from '@/features/playlists/api'
import {
  isUncertainPlaylistWrite,
  playlistDetailOptions,
  playlistErrorMessage,
} from '@/features/playlists/queries'
import { usePlaylistManagement } from '@/features/playlists/use-playlist-management'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { PageState } from '@/shared/ui/page-state'
import { PlaylistFormLayout } from './components/playlist-form-layout'
import { useToast } from '@/shared/ui/toast-context'
import { PlaylistLoading } from './components/playlist-loading'

export function PlaylistRenamePage() {
  const { playlistId = '' } = useParams()
  const [params] = useSearchParams()
  const fromLibrary = params.get('from') === 'library'
  const session = useQuery({ ...sessionOptions, refetchOnMount: false })
  const user = session.data?.id ?? ''
  const valid = z.uuid().safeParse(playlistId).success
  const query = useQuery({
    ...playlistDetailOptions(user, playlistId),
    enabled: valid && !!user && !session.isError,
  })
  const denied =
    !valid ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  return (
    <PlaylistFormLayout>
      {denied || (query.isError && !query.data) ? (
        <PageState
          kind={denied ? 'unavailable' : 'error'}
          title={denied ? '이 목록에 접근할 수 없습니다' : '불러오지 못했어요'}
          description={
            denied
              ? '목록이 없거나 현재 계정에서 볼 수 없습니다.'
              : '연결 상태를 확인한 뒤 다시 시도해 주세요.'
          }
          action={{ to: '/library/playlists', label: '내 플레이리스트' }}
          {...(!denied ? { onRetry: () => void query.refetch() } : {})}
        />
      ) : query.isPending ? (
        <PlaylistLoading />
      ) : (
        query.data && (
          <>
            <h1 className="text-xl font-bold leading-[1.5]">이름 변경</h1>
            <RenameForm
              key={`${user}:${playlistId}`}
              userId={user}
              playlist={query.data}
              fromLibrary={fromLibrary}
              load={async () => {
                const result = await query.refetch()
                if (result.error) throw result.error
                return result.data!
              }}
            />
          </>
        )
      )}
    </PlaylistFormLayout>
  )
}

function RenameForm({
  userId,
  playlist,
  fromLibrary,
  load,
}: {
  userId: string
  playlist: Playlist
  fromLibrary: boolean
  load: () => Promise<Playlist>
}) {
  const [baseline, setBaseline] = useState(playlist)
  const [name, setName] = useState(playlist.name)
  const [fieldError, setFieldError] = useState<string>()
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [reloadRequired, setReloadRequired] = useState(false)
  const [reloading, setReloading] = useState(false)
  const saving = useRef(false)
  const navigate = useNavigate()
  const notify = useToast()
  const path = `/library/playlists/${playlist.id}`
  const destination = fromLibrary ? '/library/playlists' : path
  const action = usePlaylistManagement(
    userId,
    `${path}/edit${fromLibrary ? '?from=library' : ''}`,
  )
  const changed = baseline.version !== playlist.version
  const busy = action.pending || reloading
  async function reload() {
    setReloading(true)
    try {
      setBaseline(await load())
      setReloadRequired(false)
      setError(undefined)
      setNotice(
        '최신 목록을 확인했어요. 입력한 이름을 확인한 뒤 다시 저장해 주세요.',
      )
    } catch (failure) {
      setError(playlistErrorMessage(failure))
    } finally {
      setReloading(false)
    }
  }
  async function submit() {
    if (saving.current || busy || reloadRequired || changed) return
    const parsed = playlistNameSchema.safeParse(name)
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message)
      return
    }
    saving.current = true
    setFieldError(undefined)
    setError(undefined)
    setNotice(undefined)
    try {
      const result = await action.write({
        kind: 'rename',
        playlist: baseline,
        name: parsed.data,
      })
      if (result?.kind === 'saved') {
        notify('플레이리스트 이름을 저장했어요.')
        void navigate(destination, { replace: true })
      }
    } catch (failure) {
      const field =
        failure instanceof ApiError
          ? failure.fieldErrors.find((e) => e.field === 'name')?.message
          : undefined
      if (field) setFieldError(field)
      else setError(playlistErrorMessage(failure))
      setReloadRequired(
        isUncertainPlaylistWrite(failure) ||
          (failure instanceof ApiError && [404, 409].includes(failure.status)),
      )
    } finally {
      saving.current = false
    }
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void submit()
      }}
      className="flex flex-col gap-3"
      aria-busy={busy}
    >
      <TextField
        label="플레이리스트 이름"
        value={name}
        onChange={(e) => {
          setName(e.target.value)
          setFieldError(undefined)
        }}
        disabled={busy}
        hideLabel
        placeholder="1 ~ 50자의 이름을 입력해 주세요"
        className="rounded-lg text-sm"
        {...(fieldError ? { error: fieldError } : {})}
      />
      {(error || changed) && (
        <p role="alert" className="text-sm text-error">
          {error ||
            '목록이 변경되었어요. 최신 목록을 확인한 뒤 다시 저장해 주세요.'}
        </p>
      )}
      {notice && (
        <p role="status" className="text-sm text-muted">
          {notice}
        </p>
      )}
      <div className="flex flex-wrap gap-5">
        {reloadRequired || changed ? (
          <Button disabled={busy} onClick={() => void reload()}>
            최신 목록 확인
          </Button>
        ) : (
          <Button
            type="submit"
            variant="primary"
            className="min-h-10 rounded-lg px-3 py-2"
            disabled={busy}
          >
            {busy ? '저장 중…' : '이름 저장'}
          </Button>
        )}
        <Button
          className="min-h-10 rounded-lg bg-subtle px-3 py-2 text-on-accent"
          disabled={busy}
          onClick={() => void navigate(destination)}
        >
          취소
        </Button>
      </div>
    </form>
  )
}
