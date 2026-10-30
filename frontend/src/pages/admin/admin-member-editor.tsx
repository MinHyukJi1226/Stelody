import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError } from '@/features/admin/format'
import {
  adminMemberOptions,
  getAdminMember,
  emptyMemberDraft,
  memberDraft,
  memberInput,
  validateMember,
  memberReturnPath,
  memberEditPath,
  writeMember,
  memberFields,
  type AdminMember,
  type MemberDraft,
  type SavedMember,
} from '@/features/admin/members'
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
import { MemberFields } from './member-fields'

export function AdminMemberCreatePage() {
  return <MemberEditor key="new-member" initial={null} />
}
export function AdminMemberEditPage() {
  const user = useAdminUser(),
    { memberId = '' } = useParams()
  const [params] = useSearchParams()
  const query = useQuery(adminMemberOptions(user, memberId))
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  if (query.data) return <MemberEditor key={memberId} initial={query.data} />
  const missing = query.error instanceof ApiError && query.error.status === 404
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">멤버 수정</h1>
      {query.isPending ? (
        <SongLoading />
      ) : (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">
            {missing
              ? '멤버를 찾을 수 없습니다'
              : '멤버 정보를 조회하지 못했습니다'}
          </h2>
          {!missing && (
            <Button variant="primary" onClick={() => void query.refetch()}>
              다시 조회
            </Button>
          )}
          <Button asChild>
            <Link to={memberReturnPath(params.get('returnTo'))}>
              원래 목록으로
            </Link>
          </Button>
        </section>
      )}
    </>
  )
}
function MemberEditor({ initial }: { initial: AdminMember | null }) {
  const user = useAdminUser(),
    [params] = useSearchParams()
  const returnTo = memberReturnPath(params.get('returnTo')),
    notify = useToast()
  const session = useQuery({
    ...sessionOptions,
    refetchOnMount: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
  })
  const [accessError, setAccessError] = useState<unknown>(null)
  const edit = useCatalogWrite<SavedMember | null>(
    initial ? { item: initial, possibleDuplicateIds: [] } : null,
    { user, onAccessError: setAccessError },
  )
  const [draft, setDraft] = useState(() =>
    initial ? memberDraft(initial) : emptyMemberDraft(),
  )
  const [reason, setReason] = useState(''),
    [alias, setAlias] = useState(''),
    [created, setCreated] = useState<SavedMember | null>(null)
  const base = edit.base?.item ?? null
  const dirty =
    !created &&
    (!equal(draft, base ? memberDraft(base) : emptyMemberDraft()) ||
      !!reason ||
      !!alias ||
      edit.uncertain)
  const navigation = useWorkNavigation(
    dirty,
    edit.busy,
    edit.uncertain,
    returnTo,
    '저장하지 않은 멤버 입력과 변경 사유는 버려집니다.',
  )
  const locked =
    edit.busy ||
    !session.isSuccess ||
    !isActiveAdmin(session.data) ||
    session.data?.id !== user ||
    edit.mode !== 'edit'
  function change<K extends keyof MemberDraft>(key: K, value: MemberDraft[K]) {
    setDraft((old) => ({ ...old, [key]: value }))
    edit.setErrors(
      Object.fromEntries(
        Object.entries(edit.errors).filter(
          ([field]) =>
            field !== key &&
            !field.startsWith(key + '[') &&
            !(key === 'birthday' && field.startsWith('birthday')),
        ),
      ),
    )
  }
  function addAlias() {
    const value = alias.trim()
    if (!value) return
    const normalize = (v: string) =>
      v
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
    if (locked || created) return
    const errors = validateMember(draft, reason)
    if (alias.trim())
      errors.aliases = '입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.'
    edit.setErrors(errors)
    if (!Object.keys(errors).length) edit.setMode('confirm')
  }
  async function save() {
    if (edit.mode !== 'confirm' || created) return
    await edit.save(
      (signal) =>
        writeMember(
          initial?.id ?? null,
          memberInput(draft, base?.version ?? 0, reason),
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
        {created ? '멤버를 등록했습니다' : initial ? '멤버 수정' : '멤버 등록'}
      </h1>
      {created ? (
        <section className="admin-panel" role="status">
          <p>서버의 생성 성공 응답에서 등록된 멤버를 확인했습니다.</p>
          {!!created.possibleDuplicateIds.length && (
            <div className="admin-notice">
              <h2 className="font-bold">유사한 이름의 멤버가 있습니다</h2>
              <p>
                기존 멤버와 같은 대상인지 확인해주세요. 자동으로 병합하지
                않습니다.
              </p>
              <div className="flex flex-wrap gap-2">
                {created.possibleDuplicateIds.map((id, i) => (
                  <Button key={id} asChild>
                    <Link to={memberEditPath(id, returnTo)}>
                      유사 멤버 {i + 1} 확인
                    </Link>
                  </Button>
                ))}
              </div>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            <Button asChild variant="primary">
              <Link to={memberEditPath(created.item.id, returnTo)}>
                등록한 멤버 열기
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
              YouTube 채널은 별도 채널 관리에서 등록·연결합니다. 멤버 저장으로
              채널을 함께 저장하지 않습니다.
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
                  ? '이미 멤버가 등록됐을 수 있어 다시 등록하지 않습니다. 입력을 보존했습니다. 목록 검색 후 상세 정보에서 등록 여부를 확인해주세요.'
                  : edit.mode === 'conflict'
                    ? '최초 값·내 입력·최신 값을 비교한 뒤 최신 버전으로 재확인하고 저장해주세요.'
                    : '현재 저장값이 내 입력과 같아도 이 요청의 성공을 증명하지 않습니다. 자동으로 재전송하지 않습니다.'}
              </p>
              {edit.latest && (
                <AdminTable
                  headers={['항목', '최초 조회값', '내 입력', '최신 저장값']}
                  widths={[16, 28, 28, 28]}
                  empty=""
                  rows={memberFields.map(([key, label]) => ({
                    key,
                    cells: [
                      label,
                      <MemberValue field={key} draft={memberDraft(base!)} />,
                      <MemberValue field={key} draft={draft} />,
                      <MemberValue
                        field={key}
                        draft={memberDraft(edit.latest!.item)}
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
                          item: await getAdminMember(initial.id, signal),
                          possibleDuplicateIds: [],
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
                        `/admin/members?${new URLSearchParams({ q: draft.name.trim(), page: '0', size: '20' })}`,
                      )
                    }
                  >
                    멤버 목록에서 확인
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
            <MemberFields
              draft={draft}
              change={change}
              errors={edit.errors}
              alias={alias}
              setAlias={setAlias}
              addAlias={addAlias}
              reason={reason}
              setReason={setReason}
            />
            <div className="flex flex-wrap gap-2">
              <Button variant="primary" disabled={locked} onClick={confirm}>
                {initial ? '변경 저장' : '멤버 등록'}
              </Button>
              <Button onClick={navigation.cancel}>취소</Button>
            </div>
          </fieldset>
        </>
      )}
      {edit.mode === 'confirm' && !created && (
        <Dialog
          labelledBy="member-confirm-title"
          busy={edit.busy}
          onDismiss={() => {
            if (!edit.busy) edit.setMode('edit')
          }}
        >
          <div className="grid gap-6">
            <h2 className="text-lg font-bold" id="member-confirm-title">
              {edit.busy
                ? initial
                  ? '저장 중입니다'
                  : '등록 중입니다'
                : initial
                  ? '멤버 정보를 저장할까요?'
                  : '새 멤버를 등록할까요?'}
            </h2>
            <p className="text-sm text-muted">
              {edit.busy
                ? '입력·이동·중복 제출을 잠시 막고 있습니다.'
                : '멤버 정보와 변경 사유를 확인해주세요. YouTube 채널은 함께 저장되지 않습니다.'}
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
function MemberValue({
  field,
  draft,
}: {
  field: (typeof memberFields)[number][0]
  draft: MemberDraft
}) {
  const value =
    field === 'activityStatus'
      ? draft.activityStatus === 'ACTIVE'
        ? '활동 중'
        : '졸업'
      : Array.isArray(draft[field])
        ? draft.aliases.join(' · ')
        : draft[field]
  return <span className="whitespace-pre-wrap">{value || '미등록'}</span>
}
