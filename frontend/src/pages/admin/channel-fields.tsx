import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { channelLabels, type ChannelDraft } from '@/features/admin/channels'
import { adminMemberOptions, memberListOptions } from '@/features/admin/members'
import { isAdminAccessError } from '@/features/admin/format'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { TextField } from '@/shared/ui/input'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { FieldError } from './song-ui'

export function ChannelFields({
  draft: d,
  change,
  errors,
  reason,
  setReason,
  immutable,
  user,
  onAccessError,
}: {
  draft: ChannelDraft
  change: <K extends keyof ChannelDraft>(k: K, v: ChannelDraft[K]) => void
  errors: Record<string, string>
  reason: string
  setReason: (v: string) => void
  immutable: boolean
  user: string
  onAccessError: (e: unknown) => void
}) {
  const [picker, setPicker] = useState(false),
    [choice, setChoice] = useState<'type' | 'collection' | null>(null)
  const member = useQuery({
    ...adminMemberOptions(user, d.memberId),
    enabled: !!d.memberId,
  })
  const [known, setKnown] = useState<Record<string, string>>({})
  useEffect(() => {
    if (d.memberId && isAdminAccessError(member.error))
      onAccessError(member.error)
  }, [d.memberId, member.error, onAccessError])
  return (
    <>
      <div className="song-editor-grid">
        <TextField
          label="YouTube 채널 ID*"
          value={d.youtubeId}
          readOnly={immutable}
          maxLength={24}
          onChange={(e) => change('youtubeId', e.target.value)}
          error={errors.youtubeId ?? ''}
          description={
            immutable
              ? '생성 후 변경할 수 없습니다.'
              : 'UC로 시작하는 24자 · 채널 URL이 아닌 ID'
          }
        />
        <TextField
          label="채널명*"
          value={d.name}
          maxLength={200}
          onChange={(e) => change('name', e.target.value)}
          error={errors.name ?? ''}
        />
        <div className="grid gap-2">
          <span className="text-sm font-medium">채널 유형*</span>
          <Button
            className="min-h-14 justify-start border border-line font-normal"
            disabled={immutable}
            onClick={() => setChoice('type')}
            aria-label={`채널 유형: ${channelLabels[d.channelType]}`}
          >
            {channelLabels[d.channelType]}
          </Button>
          {immutable && (
            <p className="text-xs text-muted">생성 후 변경할 수 없습니다.</p>
          )}
          <FieldError message={errors.channelType} />
        </div>
        <div className="grid gap-2">
          <span className="text-sm font-medium">연결 멤버</span>
          <p className="rounded-xl border border-line bg-surface p-4">
            {d.memberId
              ? (known[d.memberId] ??
                member.data?.name ??
                (member.isError
                  ? '멤버 이름 조회 실패'
                  : member.isPending
                    ? '멤버 조회 중…'
                    : d.memberId))
              : '없음'}
          </p>
          {member.isError && d.memberId && (
            <>
              <p className="text-xs text-muted">연결 멤버 ID: {d.memberId}</p>
              <Button
                onClick={() => {
                  if (isAdminAccessError(member.error))
                    onAccessError(member.error)
                  else void member.refetch()
                }}
              >
                멤버 다시 조회
              </Button>
            </>
          )}
          <FieldError message={errors.memberId} />
        </div>
        <div className="grid gap-2">
          <span className="text-sm font-medium">수집 사용 여부*</span>
          <Button
            className="min-h-14 justify-start border border-line font-normal"
            disabled={d.channelType === 'EXTERNAL'}
            onClick={() => setChoice('collection')}
            aria-label={`수집 사용 여부: ${d.collectionEnabled ? '사용' : '사용 안 함'}`}
          >
            {d.collectionEnabled ? '사용' : '사용 안 함'}
          </Button>
          {d.channelType === 'EXTERNAL' && (
            <p className="text-xs text-muted">
              외부 채널은 수집을 사용할 수 없습니다.
            </p>
          )}
          <FieldError message={errors.collectionEnabled} />
        </div>
        <TextField
          label="변경 사유*"
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
          error={errors.reason ?? ''}
          description="모든 등록·수정에서 필수 · 최대 500자"
        />
      </div>
      <Button
        variant="primary"
        className="justify-self-start"
        disabled={d.channelType !== 'MEMBER'}
        onClick={() => setPicker(true)}
      >
        연결 멤버 선택
      </Button>
      {picker && (
        <ChannelMemberPicker
          user={user}
          selected={d.memberId}
          onDismiss={() => setPicker(false)}
          onApply={(id, name) => {
            change('memberId', id)
            if (id && name) setKnown((old) => ({ ...old, [id]: name }))
            setPicker(false)
          }}
        />
      )}
      {choice && (
        <Dialog
          labelledBy="channel-choice-title"
          onDismiss={() => setChoice(null)}
        >
          <h2 id="channel-choice-title" className="mb-6 text-lg font-bold">
            {choice === 'type' ? '채널 유형' : '수집 사용 여부'}
          </h2>
          <div className="grid gap-3">
            {choice === 'type'
              ? Object.entries(channelLabels).map(([value, label]) => (
                  <Button
                    key={value}
                    aria-pressed={d.channelType === value}
                    onClick={() => {
                      const type = value as ChannelDraft['channelType']
                      change('channelType', type)
                      if (type !== 'MEMBER') change('memberId', '')
                      if (type === 'EXTERNAL')
                        change('collectionEnabled', false)
                      setChoice(null)
                    }}
                  >
                    {label}
                  </Button>
                ))
              : [true, false].map((value) => (
                  <Button
                    key={String(value)}
                    aria-pressed={d.collectionEnabled === value}
                    onClick={() => {
                      change('collectionEnabled', value)
                      setChoice(null)
                    }}
                  >
                    {value ? '사용' : '사용 안 함'}
                  </Button>
                ))}
            <Button onClick={() => setChoice(null)}>취소</Button>
          </div>
        </Dialog>
      )}
    </>
  )
}
function ChannelMemberPicker({
  user,
  selected,
  onApply,
  onDismiss,
}: {
  user: string
  selected: string
  onApply: (id: string, name: string) => void
  onDismiss: () => void
}) {
  const [input, setInput] = useState(''),
    [q, setQ] = useState(''),
    [page, setPage] = useState(0),
    [value, setValue] = useState(selected),
    [names, setNames] = useState<Record<string, string>>({})
  const query = useQuery(memberListOptions(user, q, page, 20))
  return (
    <Dialog
      labelledBy="channel-member-title"
      onDismiss={onDismiss}
      className="song-selector"
    >
      <h2 id="channel-member-title" className="text-lg font-bold">
        연결 멤버 선택
      </h2>
      {isAdminAccessError(query.error) ? (
        <AdminApiAccess error={query.error} user={user} />
      ) : (
        <>
          <form
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              setQ(input)
              setPage(0)
            }}
          >
            <TextField
              label="이름·별칭 검색"
              value={input}
              maxLength={200}
              onChange={(e) => setInput(e.target.value)}
            />
            <Button type="submit" disabled={query.isFetching}>
              검색
            </Button>
          </form>
          {query.isPending ? (
            <p role="status">불러오는 중…</p>
          ) : query.isError ? (
            <div role="alert">
              <p>멤버 목록을 조회하지 못했습니다.</p>
              <Button onClick={() => void query.refetch()}>다시 조회</Button>
            </div>
          ) : (
            <div className="grid gap-3">
              {[{ id: '', name: '연결하지 않음' }, ...query.data.items].map(
                (m) => (
                  <label className="song-select-row" key={m.id}>
                    <input
                      type="radio"
                      name="channel-member"
                      checked={value === m.id}
                      onChange={() => {
                        setValue(m.id)
                        setNames((old) => ({ ...old, [m.id]: m.name }))
                      }}
                    />
                    <span>{m.name}</span>
                  </label>
                ),
              )}
              {!query.data.items.length && <p>검색 결과가 없습니다.</p>}
            </div>
          )}
          <AdminPagination
            page={page}
            hasNext={query.data?.hasNext ?? false}
            pending={query.isFetching}
            onPage={setPage}
          />
          <Button
            variant="primary"
            disabled={!query.isSuccess || query.isFetching}
            onClick={() => onApply(value, names[value] ?? '')}
          >
            선택 적용
          </Button>
        </>
      )}
      <Button onClick={onDismiss}>취소</Button>
    </Dialog>
  )
}
