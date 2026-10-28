import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError } from '@/features/admin/format'
import {
  getArtist,
  getArtistRegistration,
  registerArtist,
  validateArtist,
  type Artist,
} from '@/features/admin/original-artists'
import {
  clearArtistReceipt,
  readArtistReceipt,
  storeArtistReceipt,
  type ArtistReceipt,
} from '@/features/admin/artist-registration-receipt'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { ArtistFields, type ArtistFormValue } from './artist-fields'
import { CatalogExit } from './catalog-exit'
import { Hint, ReadValue } from './song-ui'
import { useCatalogWrite } from './use-catalog-write'
import type { ArtistEditorProps } from './original-artist-form'

export function ArtistRegistration({
  onArtistSaved,
  ...props
}: ArtistEditorProps) {
  const client = useQueryClient()
  const [recovery, setRecovery] = useState(() => readArtistReceipt(props.user))
  const pending = recovery.state === 'pending' ? recovery.receipt : null
  const [value, setValue] = useState<ArtistFormValue>(() => ({
    name: pending?.body.name ?? '',
    aliases: pending?.body.aliases ?? [],
    reason: pending?.body.reason ?? '',
    alias: '',
  }))
  const edit = useCatalogWrite<Artist | null>(null, props)
  const verified = edit.verified
  const [exit, setExit] = useState(false)
  const [rejected, setRejected] = useState(false)
  const delivered = useRef(false)
  const conflicted = pending?.conflicted === true
  const query = useQuery({
    queryKey: [
      ...adminKeys(props.user),
      'artist-registration',
      pending?.requestId,
    ],
    queryFn: async ({ signal }) => {
      const result = await getArtistRegistration(pending!.requestId, signal)
      const artist =
        result.status === 'COMPLETED'
          ? await getArtist(result.artistId!, signal)
          : null
      return { result, artist }
    },
    enabled: props.enabled && !!pending && !edit.busy && !conflicted,
    retry: false,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchInterval: (query) =>
      query.state.status !== 'error' &&
      query.state.data?.result.status === 'PROCESSING'
        ? 3000
        : false,
  })
  const { onAccessError, onState, enabled } = props
  useEffect(() => {
    if (isAdminAccessError(query.error)) onAccessError(query.error)
  }, [query.error, onAccessError])
  const deliver = useCallback(
    async (artist: Artist) => {
      if (delivered.current || !enabled || !verified()) return
      delivered.current = true
      await client.invalidateQueries({
        queryKey: adminKeys(props.user),
        refetchType: 'none',
      })
      if (!verified()) {
        delivered.current = false
        return
      }
      clearArtistReceipt(props.user)
      onArtistSaved(artist, true)
    },
    [client, verified, enabled, props.user, onArtistSaved],
  )
  useEffect(() => {
    if (
      query.isSuccess &&
      !query.isFetching &&
      !edit.busy &&
      !conflicted &&
      query.data.artist
    )
      void deliver(query.data.artist)
  }, [
    query.isSuccess,
    query.isFetching,
    query.data,
    edit.busy,
    conflicted,
    deliver,
  ])
  const dirty =
    !!value.name ||
    !!value.alias ||
    !!value.reason ||
    !!value.aliases.length ||
    recovery.state !== 'none'
  const uncertain = recovery.state !== 'none'
  useEffect(() => {
    onState({ dirty, busy: edit.busy, uncertain })
  }, [dirty, edit.busy, uncertain, onState])
  function cancel() {
    if (edit.busy) return
    if (dirty) setExit(true)
    else props.onDismiss()
  }
  async function send(receipt: ArtistReceipt) {
    if (edit.busy || !edit.verified() || receipt.conflicted) return
    setRejected(false)
    await edit.save(
      async (signal) => {
        try {
          return await registerArtist(receipt.requestId, receipt.body, signal)
        } catch (error) {
          if (error instanceof ApiError && error.status === 400)
            setRejected(true)
          if (
            error instanceof ApiError &&
            error.code === 'ARTIST_REGISTRATION_REQUEST_CONFLICT'
          ) {
            const blocked = { ...receipt, conflicted: true }
            setRecovery({ state: 'pending', receipt: blocked })
            try {
              storeArtistReceipt(blocked)
            } catch {
              /* Keep the current screen blocked. */
            }
          }
          throw error
        }
      },
      (artist) => {
        if (artist) void deliver(artist)
      },
    )
  }
  function submit() {
    if (!edit.verified() || edit.busy) return
    const errors = validateArtist(value.name, value.aliases, value.reason)
    if (value.alias.trim())
      errors.aliases = '입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.'
    edit.setErrors(errors)
    if (Object.keys(errors).length) return
    const stored = readArtistReceipt(props.user)
    if (stored.state !== 'none') {
      setRecovery(stored)
      return
    }
    try {
      const receipt: ArtistReceipt = {
        user: props.user,
        requestId: crypto.randomUUID(),
        conflicted: false,
        body: {
          version: 0,
          name: value.name.trim(),
          aliases: [...value.aliases],
          reason: value.reason.trim(),
        },
      }
      storeArtistReceipt(receipt)
      setRecovery({ state: 'pending', receipt })
      void send(receipt)
    } catch (error) {
      edit.setNotice(
        error instanceof Error
          ? error.message
          : '등록 요청을 보관할 수 없어 전송하지 않았습니다.',
      )
    }
  }
  function revise() {
    const state = query.data?.result.status
    if (
      !pending ||
      edit.busy ||
      query.isFetching ||
      !(state === 'FAILED' || (state === 'NOT_PROCESSED' && rejected))
    )
      return
    clearArtistReceipt(props.user)
    if (readArtistReceipt(props.user).state !== 'none') {
      edit.setNotice(
        '보관한 요청을 정리하지 못했습니다. 새 등록은 실행하지 않았습니다.',
      )
      return
    }
    setValue({ ...pending.body, alias: '' })
    setRecovery({ state: 'none' })
    setRejected(false)
    edit.setMode('edit')
    edit.setNotice('입력을 확인한 뒤 등록해주세요. 새 요청으로 접수합니다.')
  }
  if (!props.enabled || isAdminAccessError(query.error)) return null
  const expired =
    query.error instanceof ApiError &&
    query.error.status === 410 &&
    query.error.code === 'ARTIST_REGISTRATION_RESULT_EXPIRED'
  const status = query.data?.result.status
  const failedMessage =
    query.data?.result.errorCode === 'ARTIST_REGISTRATION_INTERRUPTED'
      ? '등록 처리가 중단됐습니다. 아티스트는 생성되지 않았습니다.'
      : query.data?.result.errorCode === 'CATALOG_DATA_CONFLICT'
        ? '중복 또는 연결된 항목을 확인해주세요. 아티스트는 생성되지 않았습니다.'
        : '등록되지 않았습니다. 입력을 확인한 뒤 다시 시도해주세요.'
  return (
    <Dialog
      labelledBy="original-artist-title"
      onDismiss={cancel}
      busy={edit.busy}
      className="song-selector original-artist-dialog"
    >
      {exit ? (
        <CatalogExit
          onContinue={() => setExit(false)}
          onLeave={props.onDismiss}
          uncertain={uncertain}
        />
      ) : recovery.state === 'none' ? (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            새 아티스트 등록
          </h2>
          {edit.notice && (
            <p className="text-sm text-warning" role="status">
              {edit.notice}
            </p>
          )}
          <ArtistFields
            value={value}
            change={(patch) => setValue((old) => ({ ...old, ...patch }))}
            errors={edit.errors}
            busy={edit.busy}
            dirty={dirty}
            action="아티스트 등록"
            save={submit}
            cancel={cancel}
          />
          <Hint>
            등록 후 원곡 아티스트 선택으로 돌아갑니다. 원곡 연결은 별도로
            저장합니다.
          </Hint>
        </>
      ) : !pending ? (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            등록 요청을 복구할 수 없어요
          </h2>
          <p className="text-sm text-muted">
            {recovery.state === 'unavailable'
              ? '이 탭에 보관한 등록 요청을 읽을 수 없습니다.'
              : '보관한 등록 요청을 확인할 수 없습니다.'}{' '}
            결과를 확인할 수 없어 새 등록은 실행하지 않습니다.
          </p>
          <Button onClick={cancel}>돌아가기</Button>
        </>
      ) : (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            {edit.busy
              ? '아티스트를 등록하고 있어요'
              : conflicted
                ? '보관한 요청 내용이 일치하지 않아요'
                : expired
                  ? '등록 결과 확인 기간이 만료됐어요'
                  : query.isError
                    ? '등록 결과를 확인하지 못했어요'
                    : status === 'PROCESSING'
                      ? '아티스트를 등록하고 있어요'
                      : status === 'NOT_PROCESSED'
                        ? '등록 접수 기록이 없어요'
                        : status === 'FAILED'
                          ? '아티스트를 등록하지 못했어요'
                          : status === 'COMPLETED'
                            ? '등록한 아티스트를 확인했어요'
                            : '등록 결과를 확인하고 있어요'}
          </h2>
          <p className="text-sm text-muted">
            {edit.busy
              ? '등록이 끝날 때까지 잠시 기다려주세요.'
              : conflicted
                ? '서버에 접수된 내용과 보관한 내용이 다릅니다. 기존 요청을 유지하며 다시 등록하지 않습니다.'
                : expired
                  ? '최초 접수 후 24시간이 지나 결과를 확인할 수 없습니다. 중복 등록을 막기 위해 새 요청을 자동으로 만들지 않습니다.'
                  : query.isError
                    ? '조회 실패를 미접수나 등록 실패로 판단하지 않습니다. 보관한 요청으로 결과를 다시 확인해주세요.'
                    : status === 'PROCESSING'
                      ? '같은 요청의 결과를 3초마다 확인합니다. 창을 닫아도 보관한 요청으로 다시 확인할 수 있습니다.'
                      : status === 'NOT_PROCESSED'
                        ? '아직 본인의 접수 기록이 없습니다. 보관한 내용 그대로 같은 요청을 다시 보낼 수 있습니다.'
                        : status === 'FAILED'
                          ? failedMessage
                          : status === 'COMPLETED'
                            ? '확인된 아티스트를 선택 목록에 반영합니다. 원곡 연결은 별도로 저장합니다.'
                            : '보관한 등록 요청의 처리 결과를 확인합니다.'}
          </p>
          <ReadValue label="보관한 등록 내용">
            <strong>{pending.body.name}</strong>
            <p className="text-xs text-muted">
              별칭 · {pending.body.aliases.join(' · ') || '없음'}
            </p>
          </ReadValue>
          {edit.notice && (
            <p className="text-sm text-warning" role="status">
              {edit.notice}
            </p>
          )}
          {rejected &&
            Object.entries(edit.errors).map(([field, message]) => (
              <p className="text-xs text-error" role="alert" key={field}>
                {message}
              </p>
            ))}
          <div className="flex flex-wrap gap-2">
            {!conflicted && !expired && !edit.busy && (
              <Button
                variant="primary"
                disabled={query.isFetching}
                onClick={() => void query.refetch()}
              >
                {query.isFetching ? '결과 확인 중…' : '등록 결과 다시 확인'}
              </Button>
            )}
            {!conflicted && !query.isError && status === 'NOT_PROCESSED' && (
              <Button
                variant="primary"
                disabled={edit.busy || query.isFetching}
                onClick={() => void send(pending)}
              >
                같은 요청 다시 전송
              </Button>
            )}
            {!conflicted &&
              !query.isError &&
              (status === 'FAILED' ||
                (status === 'NOT_PROCESSED' && rejected)) && (
                <Button
                  disabled={edit.busy || query.isFetching}
                  onClick={revise}
                >
                  입력 수정
                </Button>
              )}
            <Button disabled={edit.busy} onClick={cancel}>
              돌아가기
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
