import { AuditButton, AuditScope } from './audit-button'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError } from '@/features/admin/format'
import {
  getArtists,
  getWork,
  type Work,
} from '@/features/admin/original-artists'
import {
  emptyWorkDraft,
  toWorkDraft,
  workInput,
  validateWork,
  workOptions,
  workReturnPath,
  workEditPath,
  writeWork,
  workFields,
  type WorkDraft,
  type SavedWork,
} from '@/features/admin/works'
import { equal } from '@/features/admin/song-draft'
import { sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { AdminAccess, AdminApiAccess, AdminTable } from './admin-shared'
import { SongLoading } from './admin-songs'
import { SongNav, Hint } from './song-ui'
import { WorkFields } from './work-fields'
import { useCatalogWrite, cleanGuard } from './use-catalog-write'
import { useWorkNavigation } from './use-work-navigation'
import { OriginalArtistPicker } from './original-artist-picker'

export function AdminWorkCreatePage() {
  return <WorkEditor key="new-work" initial={null} />
}
export function AdminWorkEditPage() {
  const user = useAdminUser()
  const { workId = '' } = useParams()
  const query = useQuery(workOptions(user, workId))
  const [params] = useSearchParams()
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.data) return <WorkEditor key={workId} initial={query.data} />
  const missing = query.error instanceof ApiError && query.error.status === 404
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">원곡 수정</h1>
      {query.isPending ? (
        <SongLoading />
      ) : (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">
            {missing
              ? '원곡을 찾을 수 없습니다'
              : '원곡 정보를 조회하지 못했습니다'}
          </h2>
          {!missing && (
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          )}
          <Button asChild>
            <Link to={workReturnPath(params.get('returnTo'))}>
              원래 목록으로
            </Link>
          </Button>
        </section>
      )}
    </>
  )
}
function WorkEditor({ initial }: { initial: Work | null }) {
  const user = useAdminUser()
  const [params] = useSearchParams()
  const returnTo = workReturnPath(params.get('returnTo'))
  const notify = useToast()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const [accessError, setAccessError] = useState<unknown>(null)
  const edit = useCatalogWrite<SavedWork | null>(
    initial ? { item: initial, possibleDuplicateIds: [] } : null,
    { user, onAccessError: setAccessError },
  )
  const [draft, setDraft] = useState(() =>
    initial ? toWorkDraft(initial) : emptyWorkDraft(),
  )
  const [reason, setReason] = useState('')
  const [alias, setAlias] = useState('')
  const [created, setCreated] = useState<SavedWork | null>(null)
  const [picker, setPicker] = useState(false)
  const [child, setChild] = useState(cleanGuard)
  const [known, setKnown] = useState<Record<string, string>>({})
  const base = edit.base?.item ?? null
  const dirty =
    !created &&
    (!equal(draft, base ? toWorkDraft(base) : emptyWorkDraft()) ||
      !!reason ||
      !!alias ||
      edit.uncertain ||
      child.dirty)
  const busy = edit.busy || child.busy
  const navigation = useWorkNavigation(
    dirty,
    busy,
    edit.uncertain || child.uncertain,
    returnTo,
  )
  const locked =
    busy || !edit.verified() || session.isError || edit.mode !== 'edit'
  const ids = [
    ...new Set([
      ...draft.artistIds,
      ...(base?.artistIds ?? []),
      ...(edit.latest?.item.artistIds ?? []),
    ]),
  ]
  const artists = useQuery({
    queryKey: [...adminKeys(user), 'work-editor-artists', ids],
    queryFn: ({ signal }) => getArtists(ids, signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  const names = {
    ...Object.fromEntries((artists.data ?? []).map((x) => [x.id, x.name])),
    ...known,
  }
  function change<K extends keyof WorkDraft>(key: K, value: WorkDraft[K]) {
    setDraft((old) => ({ ...old, [key]: value }))
    edit.setErrors(
      Object.fromEntries(
        Object.entries(edit.errors).filter(
          ([field]) =>
            field !== key &&
            !field.startsWith(key + '[') &&
            !field.startsWith(key + '.'),
        ),
      ),
    )
  }
  function addAlias() {
    const value = alias.trim()
    if (!value) return
    const normalize = (x: string) =>
      x
        .normalize('NFKC')
        .replace(/[\s\p{Z}]+/gu, ' ')
        .trim()
        .toLowerCase()
    if (draft.aliases.some((x) => normalize(x) === normalize(value))) {
      edit.setErrors({
        ...edit.errors,
        aliases: '공백·대소문자가 같은 별칭이 있습니다.',
      })
      return
    }
    if (draft.aliases.length >= 50) {
      edit.setErrors({ ...edit.errors, aliases: '별칭은 최대 50개입니다.' })
      return
    }
    change('aliases', [...draft.aliases, value])
    setAlias('')
  }
  function confirm() {
    if (locked || !artists.isSuccess || created) return
    const errors = validateWork(draft, reason)
    if (alias.trim())
      errors.aliases = '입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.'
    edit.setErrors(errors)
    if (!Object.keys(errors).length) edit.setMode('confirm')
  }
  async function save() {
    if (edit.mode !== 'confirm' || created || child.busy || !artists.isSuccess)
      return
    await edit.save(
      (signal) =>
        writeWork(
          initial?.id ?? null,
          workInput(draft, base?.version ?? 0, reason, base),
          signal,
        ),
      (value) => {
        if (!value) return
        if (initial) {
          notify('변경사항을 저장했습니다.')
          navigation.go(returnTo)
        } else setCreated(value)
      },
    )
  }
  async function check() {
    if (!initial) return
    await edit.check(async (signal) => ({
      item: await getWork(initial.id, signal),
      possibleDuplicateIds: [],
    }))
  }
  const error = isAdminAccessError(artists.error) ? artists.error : accessError
  if (isAdminAccessError(error))
    return (
      <>
        <AdminApiAccess error={error} user={user} />
        {navigation.dialog}
      </>
    )
  if (session.isError)
    return (
      <>
        <AdminAccess state="error" onRetry={() => void session.refetch()} />
        {navigation.dialog}
      </>
    )
  const comparing =
    edit.mode === 'conflict' ||
    edit.mode === 'unknown' ||
    edit.mode === 'checked'
  return (
    <AuditScope dirty={dirty} busy={busy} uncertain={edit.uncertain}>
      <SongNav />
      <h1 className="admin-heading">
        {created ? '원곡을 등록했습니다' : initial ? '원곡 수정' : '원곡 등록'}
      </h1>
      {base && <AuditButton kind="works" id={base.id} />}
      {created ? (
        <section className="admin-panel" role="status">
          <p>서버의 생성 성공 응답에서 등록된 원곡을 확인했습니다.</p>
          {!!created.possibleDuplicateIds.length && (
            <div className="admin-notice">
              <h2 className="font-bold">유사한 원곡이 있습니다</h2>
              <p>
                제목이 같아도 같은 원곡인지 크레딧과 링크로 확인해주세요.
                자동으로 병합하지 않습니다.
              </p>
              <div className="flex flex-wrap gap-2">
                {created.possibleDuplicateIds.map((id, i) => (
                  <Button asChild key={id}>
                    <Link to={workEditPath(id, returnTo)}>
                      유사 원곡 {i + 1} 확인
                    </Link>
                  </Button>
                ))}
              </div>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            <Button variant="primary" asChild>
              <Link to={workEditPath(created.item.id, returnTo)}>
                등록한 항목 열기
              </Link>
            </Button>
            <Button asChild>
              <Link to={returnTo}>원래 목록으로</Link>
            </Button>
          </div>
        </section>
      ) : (
        <>
          <div className="song-read-value grid gap-2">
            <h2 className="font-bold">공유 원곡 정보</h2>
            <p>
              원곡은 여러 곡이 공유하는 정보입니다. 제목·아티스트·링크·노래방
              정보 변경은 이 원곡을 연결한 다른 곡에도 적용됩니다.
            </p>
            <Hint>영향받는 곡 수는 현재 API에서 제공하지 않습니다.</Hint>
          </div>
          <div className="song-read-value">
            <Hint>
              기존 링크·TJ/KY 행을 그대로 유지하면 ID·확인 시각도 유지됩니다.
              변경되거나 추가한 행만 새로 확인됩니다.
            </Hint>
          </div>
          {edit.notice && (
            <div role="alert" className="admin-notice text-error">
              {edit.notice}
            </div>
          )}
          {Object.keys(edit.errors).length > 0 && (
            <div className="admin-notice" role="alert">
              <h2 className="font-bold">입력 내용을 확인해 주세요</h2>
              {Object.entries(edit.errors).map(([key, value]) => (
                <p key={key} className="text-sm text-error">
                  {value}
                </p>
              ))}
            </div>
          )}
          {artists.isError && (
            <div className="admin-notice" role="alert">
              <h2>아티스트 정보를 조회하지 못했습니다</h2>
              <Button onClick={() => void artists.refetch()}>다시 조회</Button>
            </div>
          )}
          {comparing && (
            <section className="admin-panel" role="status">
              <h2 className="text-lg font-bold">
                {edit.mode === 'conflict'
                  ? '다른 관리자가 먼저 변경했습니다'
                  : edit.latest
                    ? '최신 내용을 확인했습니다'
                    : '저장 결과를 확인할 수 없습니다'}
              </h2>
              <p className="text-sm text-muted">
                {!initial
                  ? '이미 원곡이 등록됐을 수 있어 다시 등록하지 않습니다. 입력을 보존했습니다. 목록 검색 후 상세 정보에서 등록 여부를 확인해주세요.'
                  : edit.mode === 'conflict'
                    ? '입력과 사유를 보존했습니다. 최초 값·내 입력·최신 값을 비교한 뒤 다시 확인하고 저장해주세요.'
                    : '응답이 유실됐을 수 있습니다. 현재 값이 내 입력과 같아도 이 요청의 성공을 증명하지 않습니다.'}
              </p>
              {initial ? (
                <>
                  {edit.latest && (
                    <AdminTable
                      headers={[
                        '항목',
                        '최초 조회값',
                        '내 입력',
                        '최신 저장값',
                      ]}
                      empty=""
                      widths={[16, 28, 28, 28]}
                      rows={workFields.map(([key, label]) => ({
                        key,
                        cells: [
                          label,
                          <WorkValue
                            field={key}
                            draft={toWorkDraft(base!)}
                            names={names}
                          />,
                          <WorkValue field={key} draft={draft} names={names} />,
                          <WorkValue
                            field={key}
                            draft={toWorkDraft(edit.latest!.item)}
                            names={names}
                          />,
                        ],
                      }))}
                    />
                  )}
                  <div className="flex flex-wrap gap-2">
                    {edit.latest ? (
                      <Button
                        variant="primary"
                        disabled={busy || !artists.isSuccess}
                        onClick={edit.review}
                      >
                        입력 재확인
                      </Button>
                    ) : (
                      <Button
                        variant="primary"
                        disabled={busy}
                        onClick={() => void check()}
                      >
                        {busy ? '조회 중…' : '최신 저장값 조회'}
                      </Button>
                    )}

                    <Button disabled={busy} onClick={navigation.cancel}>
                      목록으로
                    </Button>
                  </div>
                </>
              ) : (
                <Button
                  onClick={() =>
                    navigation.request(
                      `/admin/works?${new URLSearchParams({ q: draft.title.trim(), page: '0', size: '20' })}`,
                    )
                  }
                >
                  원곡 목록에서 확인
                </Button>
              )}
            </section>
          )}
          <fieldset
            disabled={locked || !!created}
            className="grid min-w-0 gap-6"
            aria-busy={busy}
          >
            <WorkFields
              draft={draft}
              change={change}
              base={base}
              errors={edit.errors}
              names={names}
              alias={alias}
              setAlias={setAlias}
              addAlias={addAlias}
              reason={reason}
              setReason={setReason}
              selectArtists={() => setPicker(true)}
            />
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                disabled={locked || !artists.isSuccess}
                onClick={confirm}
              >
                {initial ? '변경 저장' : '원곡 등록'}
              </Button>
              <Button onClick={navigation.cancel}>취소</Button>
            </div>
          </fieldset>
          {comparing && (
            <Button onClick={navigation.cancel} disabled={busy}>
              원래 목록으로
            </Button>
          )}
        </>
      )}
      {edit.mode === 'confirm' && !created && (
        <Dialog
          labelledBy="work-confirm-title"
          onDismiss={() => {
            if (!edit.busy) edit.setMode('edit')
          }}
          busy={edit.busy}
        >
          <div className="grid gap-6">
            <h2 id="work-confirm-title" className="text-lg font-bold">
              {edit.busy
                ? initial
                  ? '저장 중입니다'
                  : '등록 중입니다'
                : initial
                  ? '공유 원곡 정보를 변경할까요?'
                  : '새 원곡을 등록할까요?'}
            </h2>
            <p className="text-sm text-muted">
              {edit.busy
                ? '입력·이동·중복 제출을 잠시 막고 있습니다.'
                : initial
                  ? '이 원곡을 공유하는 다른 곡에도 적용됩니다. 변경 사유와 내용을 확인하세요.'
                  : '등록할 입력과 사유를 확인하세요. 아티스트 정보 저장과는 별개입니다.'}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                disabled={edit.busy}
                onClick={() => void save()}
              >
                {initial ? '확인 후 저장' : '등록'}
              </Button>
              <Button disabled={edit.busy} onClick={() => edit.setMode('edit')}>
                취소
              </Button>
            </div>
          </div>
        </Dialog>
      )}
      {picker && (
        <OriginalArtistPicker
          user={user}
          workId={initial?.id ?? null}
          enabled={!session.isError && !isAdminAccessError(error)}
          onAccessError={setAccessError}
          onState={setChild}
          onSaved={() => {}}
          selected={draft.artistIds}
          onArtistChange={(item) => {
            setKnown((old) => ({ ...old, [item.id]: item.name }))
          }}
          onApply={(rows) => {
            change(
              'artistIds',
              rows.map((x) => x.id),
            )
            setKnown((old) => ({
              ...old,
              ...Object.fromEntries(rows.map((x) => [x.id, x.name])),
            }))
            setPicker(false)
            setChild(cleanGuard)
          }}
          onDismiss={() => {
            setPicker(false)
            setChild(cleanGuard)
          }}
        />
      )}
      {navigation.dialog}
    </AuditScope>
  )
}
function WorkValue({
  field,
  draft,
  names,
}: {
  field: (typeof workFields)[number][0]
  draft: WorkDraft
  names: Record<string, string>
}) {
  let text: string
  if (field === 'artistIds')
    text = draft.artistIds
      .map((id) => names[id] ?? `아티스트 ID: ${id}`)
      .join(' · ')
  else if (field === 'links')
    text = draft.links
      .map((x) => `${x.platform} · ${x.url}\n근거 ${x.sourceUrl}`)
      .join('\n')
  else if (field === 'karaoke')
    text = draft.karaoke
      .map(
        (x) =>
          `${x.provider} · ${x.status === 'UNKNOWN' ? '미확인' : x.status === 'REGISTERED' ? '등록 확인' : '미등록 확인'} · ${x.number || '번호 없음'} · ${x.sourceUrl || '근거 없음'}`,
      )
      .join('\n')
  else if (field === 'aliases') text = draft.aliases.join(' · ')
  else text = draft.title
  return <span className="whitespace-pre-wrap">{text || '없음'}</span>
}
