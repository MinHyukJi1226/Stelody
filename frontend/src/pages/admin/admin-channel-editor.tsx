import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError } from '@/features/admin/format'
import {
  adminChannelOptions,
  getAdminChannel,
  emptyChannelDraft,
  channelDraft,
  channelInput,
  validateChannel,
  channelReturnPath,
  channelEditPath,
  writeChannel,
  channelFields,
  channelLabels,
  type AdminChannel,
  type ChannelDraft,
  type SavedChannel,
} from '@/features/admin/channels'
import { equal } from '@/features/admin/song-draft'
import { sessionOptions } from '@/features/auth/session'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { AdminAccess, AdminApiAccess, AdminTable } from './admin-shared'
import { SongLoading } from './admin-songs'
import { SongNav } from './song-ui'
import { useCatalogWrite } from './use-catalog-write'
import { useWorkNavigation } from './use-work-navigation'
import { ChannelFields } from './channel-fields'

export function AdminChannelCreatePage() {
  return <ChannelEditor key="new-channel" initial={null} />
}
export function AdminChannelEditPage() {
  const user = useAdminUser(),
    { channelId = '' } = useParams()
  const [params] = useSearchParams()
  const query = useQuery(adminChannelOptions(user, channelId))
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.data) return <ChannelEditor key={channelId} initial={query.data} />
  const missing = query.error instanceof ApiError && query.error.status === 404
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">채널 수정</h1>
      {query.isPending ? (
        <SongLoading />
      ) : (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">
            {missing
              ? '채널을 찾을 수 없습니다'
              : '채널 정보를 조회하지 못했습니다'}
          </h2>
          {!missing && (
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          )}
          <Button asChild>
            <Link to={channelReturnPath(params.get('returnTo'))}>
              원래 목록으로
            </Link>
          </Button>
        </section>
      )}
    </>
  )
}
function ChannelEditor({ initial }: { initial: AdminChannel | null }) {
  const user = useAdminUser(),
    [params] = useSearchParams()
  const returnTo = channelReturnPath(params.get('returnTo')),
    notify = useToast()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const [accessError, setAccessError] = useState<unknown>(null)
  const edit = useCatalogWrite<SavedChannel | null>(
    initial ? { item: initial } : null,
    { user, onAccessError: setAccessError },
  )
  const [draft, setDraft] = useState(() =>
    initial ? channelDraft(initial) : emptyChannelDraft(),
  )
  const [reason, setReason] = useState(''),
    [created, setCreated] = useState<SavedChannel | null>(null)
  const base = edit.base?.item ?? null
  const dirty =
    !created &&
    (!equal(draft, base ? channelDraft(base) : emptyChannelDraft()) ||
      !!reason ||
      edit.uncertain)
  const navigation = useWorkNavigation(
    dirty,
    edit.busy,
    edit.uncertain,
    returnTo,
    '저장하지 않은 채널 입력과 변경 사유는 버려집니다.',
  )
  const locked =
    edit.busy ||
    !session.isSuccess ||
    !isActiveAdmin(session.data) ||
    session.data?.id !== user ||
    edit.mode !== 'edit'
  function change<K extends keyof ChannelDraft>(
    key: K,
    value: ChannelDraft[K],
  ) {
    setDraft((old) => ({ ...old, [key]: value }))
    edit.setErrors(
      Object.fromEntries(
        Object.entries(edit.errors).filter(
          ([field]) => field !== key && !field.startsWith(key + '['),
        ),
      ),
    )
  }
  function confirm() {
    if (locked || created) return
    const errors = validateChannel(draft, reason)
    edit.setErrors(errors)
    if (!Object.keys(errors).length) edit.setMode('confirm')
  }
  async function save() {
    if (edit.mode !== 'confirm' || created) return
    await edit.save(
      (signal) =>
        writeChannel(
          initial?.id ?? null,
          channelInput(draft, base?.version ?? 0, reason),
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
  if (isAdminAccessError(accessError))
    return (
      <>
        <AdminApiAccess error={accessError} user={user} />
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
  const comparing = ['conflict', 'unknown', 'checked'].includes(edit.mode)
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">
        {created
          ? '채널을 등록했습니다'
          : initial
            ? '공식 채널 수정'
            : '공식 채널 등록'}
      </h1>
      {created ? (
        <section className="admin-panel" role="status">
          <p>서버의 생성 성공 응답에서 등록된 채널을 확인했습니다.</p>
          <div className="flex flex-wrap gap-2">
            <Button asChild variant="primary">
              <Link to={channelEditPath(created.item.id, returnTo)}>
                등록한 채널 열기
              </Link>
            </Button>
            <Button asChild>
              <Link to={returnTo}>원래 목록으로</Link>
            </Button>
          </div>
        </section>
      ) : (
        <>
          <section className="admin-panel gap-2">
            <h2 className="font-bold">확인해 주세요</h2>
            <p className="text-sm text-muted">
              채널 ID·채널 유형은 생성 후 변경할 수 없습니다. 변경이 필요하면 새
              채널을 등록하세요.
            </p>
          </section>
          <section className="admin-panel gap-2">
            <h2 className="font-bold">확인해 주세요</h2>
            <p className="text-sm text-muted">
              멤버 프로필과 채널은 별도로 저장됩니다. 채널 등록으로 멤버를
              생성하지 않습니다.
            </p>
          </section>
          {edit.notice && (
            <p className="admin-notice text-error" role="alert">
              {edit.notice}
            </p>
          )}
          {!!Object.keys(edit.errors).length && (
            <div className="admin-notice" role="alert">
              <h2 className="font-bold">입력 내용을 확인해 주세요</h2>
              {Object.entries(edit.errors).map(([k, v]) => (
                <p key={k} className="text-sm text-error">
                  {v}
                </p>
              ))}
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
                  ? '이미 채널가 등록됐을 수 있어 다시 등록하지 않습니다. 입력을 보존했습니다. 목록 검색 후 상세 정보에서 등록 여부를 확인해주세요.'
                  : edit.mode === 'conflict'
                    ? '최초 값·내 입력·최신 값을 비교한 뒤 최신 버전으로 재확인하고 저장해주세요.'
                    : '현재 저장값이 내 입력과 같아도 이 요청의 성공을 증명하지 않습니다. 자동으로 재전송하지 않습니다.'}
              </p>
              {edit.latest && (
                <AdminTable
                  headers={['항목', '최초 조회값', '내 입력', '최신 저장값']}
                  widths={[16, 28, 28, 28]}
                  empty=""
                  rows={channelFields.map(([key, label]) => ({
                    key,
                    cells: [
                      label,
                      <ChannelValue field={key} draft={channelDraft(base!)} />,
                      <ChannelValue field={key} draft={draft} />,
                      <ChannelValue
                        field={key}
                        draft={channelDraft(edit.latest!.item)}
                      />,
                    ],
                  }))}
                />
              )}
              <div className="flex flex-wrap gap-2">
                {initial ? (
                  edit.latest ? (
                    <Button
                      variant="primary"
                      disabled={edit.busy}
                      onClick={edit.review}
                    >
                      입력 재확인
                    </Button>
                  ) : (
                    <Button
                      variant="primary"
                      disabled={edit.busy}
                      onClick={() =>
                        void edit.check(async (signal) => ({
                          item: await getAdminChannel(initial.id, signal),
                        }))
                      }
                    >
                      {edit.busy ? '조회 중…' : '최신 저장값 조회'}
                    </Button>
                  )
                ) : (
                  <Button
                    onClick={() =>
                      navigation.request(
                        `/admin/channels?${new URLSearchParams({ q: draft.youtubeId.trim() || draft.name.trim(), page: '0', size: '20' })}`,
                      )
                    }
                  >
                    채널 목록에서 확인
                  </Button>
                )}
                <Button disabled>변경 이력 · 준비 중</Button>
                <Button disabled={edit.busy} onClick={navigation.cancel}>
                  원래 목록으로
                </Button>
              </div>
            </section>
          )}
          <fieldset
            className="grid min-w-0 gap-6"
            disabled={locked}
            aria-busy={edit.busy}
          >
            <ChannelFields
              draft={draft}
              immutable={!!initial}
              user={user}
              onAccessError={setAccessError}
              change={change}
              errors={edit.errors}
              reason={reason}
              setReason={setReason}
            />
            <div className="flex flex-wrap gap-2">
              <Button variant="primary" disabled={locked} onClick={confirm}>
                {initial ? '변경 저장' : '채널 등록'}
              </Button>
              <Button onClick={navigation.cancel}>취소</Button>
            </div>
          </fieldset>
        </>
      )}
      {edit.mode === 'confirm' && !created && (
        <Dialog
          labelledBy="channel-confirm-title"
          busy={edit.busy}
          onDismiss={() => {
            if (!edit.busy) edit.setMode('edit')
          }}
        >
          <div className="grid gap-6">
            <h2 className="text-lg font-bold" id="channel-confirm-title">
              {edit.busy
                ? initial
                  ? '저장 중입니다'
                  : '등록 중입니다'
                : initial
                  ? '채널 정보를 저장할까요?'
                  : '새 채널을 등록할까요?'}
            </h2>
            <p className="text-sm text-muted">
              {edit.busy
                ? '입력·이동·중복 제출을 잠시 막고 있습니다.'
                : '채널 정보와 변경 사유를 확인해주세요. 공식 채널의 수집 사용 여부와 연결 멤버를 확인해주세요.'}
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
      {navigation.dialog}
    </>
  )
}
function ChannelValue({
  field,
  draft,
}: {
  field: (typeof channelFields)[number][0]
  draft: ChannelDraft
}) {
  const value =
    field === 'collectionEnabled'
      ? draft.collectionEnabled
        ? '사용'
        : '사용 안 함'
      : field === 'channelType'
        ? channelLabels[draft.channelType]
        : draft[field]
  return <span className="whitespace-pre-wrap">{value || '미등록'}</span>
}
