import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/shared/ui/button'
import { Input, TextField } from '@/shared/ui/input'
import { useToast } from '@/shared/ui/toast-context'
import { ApiError } from '@/shared/api/errors'
import { playlistNameSchema, type Playlist } from './api'
import {
  isUncertainPlaylistWrite,
  playlistDetailOptions,
  playlistErrorMessage,
} from './queries'
import { usePlaylistWrite } from './use-playlist-write'

export function PlaylistNameForm({
  userId,
  returnTo,
  songId,
  onDone,
  onCancel,
  modal = false,
  busyChanged,
}: {
  userId: string
  returnTo: string
  songId?: string
  onDone: (playlist: Playlist) => void
  onCancel: () => void
  modal?: boolean
  busyChanged?: (busy: boolean) => void
}) {
  const [name, setName] = useState('')
  const [fieldError, setFieldError] = useState<string>()
  const [error, setError] = useState<string>()
  const [uncertain, setUncertain] = useState(false)
  const [created, setCreated] = useState<Playlist>()
  const saving = useRef(false)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (modal) input.current?.focus()
  }, [modal])
  const client = useQueryClient()
  const notify = useToast()
  const action = usePlaylistWrite(userId, returnTo)
  const id = useId()
  async function submit() {
    if (saving.current || action.pending || uncertain) return
    const parsed = playlistNameSchema.safeParse(name)
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message)
      return
    }
    saving.current = true
    busyChanged?.(true)
    setFieldError(undefined)
    setError(undefined)
    let playlist = created
    try {
      if (!playlist) {
        playlist = await action.write({
          kind: 'create',
          name: parsed.data,
          ...(songId ? { songId } : {}),
        })
        if (!playlist) return
        setCreated(playlist)
      }
      if (songId) {
        // A failed add keeps the created list, so retry never creates a second list.
        if (created)
          playlist = await client.fetchQuery({
            ...playlistDetailOptions(userId, playlist.id),
            staleTime: 0,
          })
        const added = await action.write({ kind: 'add', playlist, songId })
        if (!added) return
        playlist = added
      }
      notify(
        songId ? '플레이리스트에 추가했어요.' : '플레이리스트를 만들었어요.',
      )
      onDone(playlist)
    } catch (failure) {
      if (!playlist && isUncertainPlaylistWrite(failure)) setUncertain(true)
      const nameError =
        failure instanceof ApiError
          ? failure.fieldErrors.find((e) => e.field === 'name')?.message
          : undefined
      if (nameError) setFieldError(nameError)
      else
        setError(
          playlist && songId
            ? isUncertainPlaylistWrite(failure)
              ? `목록은 만들어졌지만 곡 추가 결과를 확인하지 못했어요. ${playlistErrorMessage(failure)}`
              : `목록은 만들어졌지만 곡을 담지 못했어요. ${playlistErrorMessage(failure)}`
            : playlistErrorMessage(failure),
        )
    } finally {
      saving.current = false
      busyChanged?.(false)
    }
  }
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
      className={`flex w-full flex-col ${modal ? 'gap-4' : 'gap-3'}`}
      aria-busy={action.pending}
    >
      {modal ? (
        <div className="flex flex-col gap-4">
          <label
            htmlFor={`${id}-name`}
            className="text-sm leading-[1.5] text-muted"
          >
            이름
          </label>
          <Input
            ref={input}
            id={`${id}-name`}
            placeholder="좋아하는 노래"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
              setFieldError(undefined)
            }}
            disabled={action.pending || !!created || uncertain}
            className="rounded-lg border-0 bg-bg"
            aria-invalid={!!fieldError}
            aria-describedby={fieldError || error ? id : undefined}
          />
          {fieldError && (
            <p id={id} className="text-xs text-error">
              {fieldError}
            </p>
          )}
        </div>
      ) : (
        <TextField
          label="플레이리스트 이름"
          hideLabel
          placeholder="1 ~ 50자의 이름을 입력해 주세요"
          className="rounded-lg text-sm"
          value={name}
          onChange={(event) => {
            setName(event.target.value)
            setFieldError(undefined)
          }}
          disabled={action.pending || !!created || uncertain}
          {...(fieldError ? { error: fieldError } : {})}
          aria-describedby={error ? id : undefined}
        />
      )}
      {error && (
        <p id={id} role="alert" className="text-sm text-error">
          {error}
        </p>
      )}
      <div className={modal ? 'flex flex-wrap gap-2' : 'flex flex-wrap gap-5'}>
        {modal && (
          <Button onClick={onCancel} disabled={action.pending}>
            취소
          </Button>
        )}
        <Button
          type="submit"
          variant="primary"
          className={modal ? undefined : 'min-h-10 rounded-lg px-3 py-2'}
          disabled={action.pending || uncertain}
        >
          {action.pending
            ? '저장 중…'
            : created && songId
              ? '곡 담기 다시 시도'
              : modal
                ? '만들고 담기'
                : '생성하기'}
        </Button>
        {!modal && (
          <Button
            className="min-h-10 rounded-lg bg-subtle px-3 py-2 text-on-accent"
            onClick={onCancel}
            disabled={action.pending}
          >
            취소
          </Button>
        )}
        {uncertain && (
          <Button asChild>
            <Link
              to="/library/playlists"
              onClick={modal ? onCancel : undefined}
            >
              목록 확인
            </Link>
          </Button>
        )}
      </div>
    </form>
  )
}
