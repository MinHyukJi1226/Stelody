import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { adminKeys } from '@/features/admin/queries'
import {
  getWork,
  getArtists,
  saveWork,
  type Work,
  type Artist,
} from '@/features/admin/original-artists'
import { isAdminAccessError } from '@/features/admin/format'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { TextField } from '@/shared/ui/input'
import { useToast } from '@/shared/ui/toast-context'
import { FieldError, Hint, ReadValue } from './song-ui'
import { CatalogWriteStatus } from './catalog-write-status'
import {
  useCatalogWrite,
  cleanGuard,
  type CatalogGuard,
  type EditGuard,
} from './use-catalog-write'
import { OriginalArtistPicker } from './original-artist-picker'
import { CatalogExit } from './catalog-exit'

export type WorkArtistProps = CatalogGuard & {
  workId: string | null
  enabled: boolean
  onDismiss: () => void
  onState: (state: EditGuard) => void
  onSaved: () => void
}
export function WorkArtistEditor(props: WorkArtistProps) {
  const query = useQuery({
    queryKey: [...adminKeys(props.user), 'work-artists', props.workId],
    queryFn: async ({ signal }) => {
      const work = await getWork(props.workId!, signal)
      return { work, artists: await getArtists(work.artistIds, signal) }
    },
    enabled: !!props.workId && props.enabled,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  const { onAccessError } = props
  useEffect(() => {
    if (isAdminAccessError(query.error)) onAccessError(query.error)
  }, [query.error, onAccessError])
  if (isAdminAccessError(query.error)) return null
  if (query.data)
    return (
      <LoadedWorkArtists
        {...props}
        initial={query.data.work}
        artists={query.data.artists}
      />
    )
  if (!props.enabled || isAdminAccessError(query.error)) return null
  const missing =
    !props.workId ||
    (query.error instanceof ApiError && query.error.status === 404)
  return (
    <Dialog
      labelledBy="original-artist-title"
      onDismiss={props.onDismiss}
      className="song-selector original-artist-dialog"
    >
      <h2 id="original-artist-title" className="text-lg font-bold">
        {missing
          ? '원곡을 확인할 수 없어요'
          : query.isError
            ? '원곡 정보를 조회하지 못했어요'
            : '원곡 정보 확인 중…'}
      </h2>
      {query.isPending && props.workId ? (
        <div
          className="h-40 animate-pulse rounded-xl bg-bg"
          aria-label="불러오는 중"
        />
      ) : (
        <p className="text-sm text-muted">
          {missing
            ? '원곡이 삭제됐거나 연결이 없습니다. 다른 원곡을 선택해 주세요.'
            : '다시 조회해주세요. 입력 중인 곡 정보는 유지됩니다.'}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {!missing && query.isError && (
          <Button variant="primary" onClick={() => void query.refetch()}>
            다시 조회
          </Button>
        )}
        <Button onClick={props.onDismiss}>
          {missing ? '곡 수정으로 돌아가기' : '돌아가기'}
        </Button>
      </div>
    </Dialog>
  )
}
function LoadedWorkArtists({
  initial,
  artists,
  ...props
}: WorkArtistProps & { initial: Work; artists: Artist[] }) {
  const edit = useCatalogWrite(initial, props)
  const notify = useToast()
  const [ids, setIds] = useState(initial.artistIds)
  const [names, setNames] = useState<Record<string, string>>(() =>
    Object.fromEntries(artists.map((x) => [x.id, x.name])),
  )
  const [reason, setReason] = useState('')
  const [picker, setPicker] = useState(false)
  const [exit, setExit] = useState(false)
  const [childGuard, setChildGuard] = useState(cleanGuard)
  const dirty =
    JSON.stringify(ids) !== JSON.stringify(edit.base.artistIds) ||
    !!reason ||
    edit.uncertain
  const { onState } = props
  useEffect(() => {
    onState({
      dirty: dirty || childGuard.dirty,
      busy: edit.busy || childGuard.busy,
      uncertain: edit.uncertain || childGuard.uncertain,
    })
  }, [dirty, edit.busy, edit.uncertain, childGuard, onState])
  const named = (value: string[]) =>
    value.length
      ? value.map((id) => names[id] ?? '이름 확인 필요').join(' · ')
      : '아티스트 없음'
  function cancel() {
    if (edit.busy) return
    if (dirty) setExit(true)
    else props.onDismiss()
  }
  function confirm() {
    if (!reason.trim() || reason.length > 500) {
      edit.setErrors({ reason: '변경 사유는 1–500자로 입력해주세요.' })
      return
    }
    edit.setErrors({})
    edit.setMode('confirm')
  }
  async function check() {
    await edit.check(async (signal) => {
      const work = await getWork(edit.base.id, signal)
      const rows = await getArtists(
        [...new Set([...work.artistIds, ...initial.artistIds, ...ids])],
        signal,
      )
      setNames((old) => ({
        ...old,
        ...Object.fromEntries(rows.map((x) => [x.id, x.name])),
      }))
      return work
    })
  }
  if (picker)
    return (
      <OriginalArtistPicker
        {...props}
        selected={ids}
        onState={setChildGuard}
        onArtistChange={(item, created) => {
          setNames((old) => ({ ...old, [item.id]: item.name }))
          edit.setNotice(
            created
              ? '아티스트를 등록했습니다. 아직 이 원곡에는 저장되지 않았습니다.'
              : '아티스트 정보를 저장했습니다. 원곡 연결은 별도로 저장합니다.',
          )
        }}
        onApply={(rows) => {
          setIds(rows.map((x) => x.id))
          setNames((old) => ({
            ...old,
            ...Object.fromEntries(rows.map((x) => [x.id, x.name])),
          }))
          setPicker(false)
          setChildGuard(cleanGuard)
        }}
        onDismiss={() => {
          setPicker(false)
          setChildGuard(cleanGuard)
        }}
        onSaved={props.onSaved}
      />
    )
  if (!props.enabled) return null
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
          uncertain={edit.uncertain}
        />
      ) : edit.mode === 'confirm' ? (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            원곡 아티스트를 저장할까요?
          </h2>
          <p className="text-sm text-muted">
            이 원곡을 연결한 다른 곡에도 아티스트 변경이 반영됩니다. 곡의 미저장
            입력은 그대로 유지됩니다.
          </p>
          <ReadValue label="저장할 원곡 아티스트">
            {ids.length
              ? named(ids)
              : '아티스트 없음 · 이 원곡의 아티스트 연결을 모두 해제합니다.'}
          </ReadValue>
          <Hint>
            아티스트 정보 자체와 원곡의 제목·별칭·링크·노래방 정보는 유지합니다.
          </Hint>
          <div className="flex flex-wrap gap-2">
            <Button disabled={edit.busy} onClick={() => edit.setMode('edit')}>
              취소
            </Button>
            <Button
              variant="primary"
              disabled={edit.busy}
              onClick={() =>
                void edit.save(
                  (signal) => saveWork(edit.base, ids, reason, signal),
                  () => {
                    setReason('')
                    props.onSaved()
                    notify('원곡 아티스트를 저장했습니다.')
                    props.onDismiss()
                  },
                )
              }
            >
              {edit.busy ? '저장 중…' : '원곡 아티스트 저장'}
            </Button>
          </div>
        </>
      ) : edit.mode !== 'edit' ? (
        <CatalogWriteStatus
          mode={edit.mode}
          busy={edit.busy}
          notice={edit.notice}
          base={named(edit.base.artistIds)}
          draft={named(ids)}
          latest={edit.latest ? named(edit.latest.artistIds) : null}
          check={() => void check()}
          review={edit.review}
          back={cancel}
        />
      ) : (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            원곡 아티스트 편집
          </h2>
          <p className="text-sm text-muted">원곡 · {edit.base.title}</p>
          {edit.notice && (
            <p className="song-read-value" role="status">
              {edit.notice}
            </p>
          )}
          <div className="song-read-value grid gap-2">
            <strong>이 원곡을 사용하는 곡에 함께 반영돼요</strong>
            <p>
              원곡 아티스트만 별도로 저장합니다. 입력 중인 곡 정보는 그대로
              유지됩니다.
            </p>
          </div>
          <fieldset disabled={edit.busy} className="grid gap-6">
            <div className="grid gap-2">
              <span className="text-sm font-medium">
                원곡 아티스트 · 여러 명 선택 가능
              </span>
              <div className="flex flex-wrap gap-2">
                {ids.length ? (
                  ids.map((id) => (
                    <Button
                      key={id}
                      aria-label={`${names[id] ?? '아티스트'} 선택 제거`}
                      onClick={() =>
                        setIds((old) => old.filter((x) => x !== id))
                      }
                    >
                      {names[id] ?? '이름 확인 필요'} ×
                    </Button>
                  ))
                ) : (
                  <p className="text-sm text-muted">아티스트 없음</p>
                )}
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setPicker(true)}>
                아티스트 찾기·추가
              </Button>
              <Button disabled={!ids.length} onClick={() => setIds([])}>
                선택 해제
              </Button>
            </div>
            <FieldError message={edit.errors.artistIds} />
            <TextField
              label="변경 사유*"
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
              error={edit.errors.reason ?? ''}
              description="최대 500자"
            />
            {Object.entries(edit.errors)
              .filter(([k]) => !['reason', 'artistIds'].includes(k))
              .map(([k, v]) => (
                <FieldError key={k} message={v} />
              ))}
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                disabled={!dirty || !reason.trim()}
                onClick={confirm}
              >
                원곡 아티스트 저장
              </Button>
              <Button onClick={cancel}>취소</Button>
            </div>
          </fieldset>
          <Hint>이 저장은 곡 저장·후보 등록과 별도로 반영됩니다.</Hint>
        </>
      )}
    </Dialog>
  )
}
