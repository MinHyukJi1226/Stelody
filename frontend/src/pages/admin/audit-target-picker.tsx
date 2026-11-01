import { Dialog } from '@/shared/ui/dialog'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { adminKeys } from '@/features/admin/queries'
import { getAdminSong } from '@/features/admin/songs'
import { useAdminUser } from '@/features/admin/admin-context'
import { isAdminAccessError } from '@/features/admin/format'
import { auditKinds, type AuditKind } from '@/features/admin/audit'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Choices } from './song-ui'
import { AdminApiAccess, AdminPagination } from './admin-shared'
import { type AuditTarget } from './admin-audit'
import { SongLoading } from './admin-songs'
const pageSchema = z.object({
  items: z.array(
    z.object({
      id: z.uuid(),
      name: z.string().optional(),
      title: z.string().nullable().optional(),
    }),
  ),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  hasNext: z.boolean(),
})
type Item = { id: string; name: string }
export function AuditTargetPicker({
  kind,
  onSelect,
  onBack,
}: {
  kind: AuditKind
  onSelect: (t: AuditTarget) => void
  onBack: () => void
}) {
  const [origin, setOrigin] = useState(kind === 'artists')
  const [stages, setStages] = useState<
    { kind: AuditKind; owner: Item | null }[]
  >([{ kind: kind === 'videos' ? 'songs' : kind, owner: null }])
  const [selected, setSelected] = useState<AuditTarget | null>(
    kind === 'collection-rules'
      ? { kind, id: null, name: '현재 수집 분류 규칙' }
      : null,
  )
  const stage = stages.at(-1)!
  function choose(item: Item) {
    if ((kind === 'artists' || kind === 'videos') && !stage.owner)
      setStages([...stages, { kind, owner: item }])
    else setSelected({ kind, id: item.id, name: item.name })
  }
  function back() {
    if (selected) setSelected(null)
    else if (stages.length > 1) setStages(stages.slice(0, -1))
    else if (kind === 'artists' && !origin) setOrigin(true)
    else onBack()
  }
  return (
    <Dialog
      labelledBy="audit-pick-title"
      onDismiss={back}
      className="max-w-[640px] p-6"
    >
      <div className="grid gap-4">
        <h1 id="audit-pick-title" className="text-2xl font-bold">
          {selected ? '조회 대상 선택 완료' : `${auditKinds[kind]} 선택`}
        </h1>
        <p className="text-sm text-muted">
          {stage.owner
            ? `선택한 대상 · ${stage.owner.name}`
            : '이력을 조회할 기존 대상 하나를 선택하세요.'}
        </p>
        <div className="grid min-w-0 gap-4">
          {selected && (
            <section className="admin-panel">
              <h2 className="text-lg font-bold">
                {auditKinds[kind]} · {selected.name}
              </h2>
              <p>선택한 대상의 변경 이력만 조회합니다.</p>
              <Button variant="primary" onClick={() => onSelect(selected)}>
                이력 보기
              </Button>
            </section>
          )}
          {origin && (
            <section className="admin-panel">
              <h2 className="text-lg font-bold">아티스트를 찾을 화면</h2>
              <p>기존 곡·원곡 화면의 아티스트 선택·편집창을 사용합니다.</p>
              {(['songs', 'works'] as const).map((k) => (
                <Button
                  key={k}
                  onClick={() => {
                    setStages([{ kind: k, owner: null }])
                    setOrigin(false)
                  }}
                >
                  {k === 'songs' ? '곡에서 찾기' : '원곡에서 찾기'}
                </Button>
              ))}
            </section>
          )}
          {!origin &&
            stages.map((s, i) => (
              <div
                key={`${s.kind}:${s.owner?.id ?? ''}`}
                hidden={!!selected || i !== stages.length - 1}
              >
                <PickerList kind={s.kind} owner={s.owner} onSelect={choose} />
              </div>
            ))}
        </div>
        <Button onClick={back}>
          {selected
            ? '선택 목록으로'
            : stages.length > 1
              ? '이전 선택으로'
              : '대상 종류로'}
        </Button>
      </div>
    </Dialog>
  )
}

function PickerList({
  kind,
  owner,
  onSelect,
}: {
  kind: AuditKind
  owner: Item | null
  onSelect: (i: Item) => void
}) {
  const user = useAdminUser(),
    [input, setInput] = useState(''),
    [q, setQ] = useState(''),
    [page, setPage] = useState(0),
    [status, setStatus] = useState('ALL'),
    [disposition, setDisposition] = useState('')
  const query = useQuery({
    queryKey: [
      ...adminKeys(user),
      'audit-select',
      kind,
      owner?.id,
      q,
      page,
      status,
      disposition,
    ],
    queryFn: async ({
      signal,
    }): Promise<{ items: Item[]; hasNext: boolean }> => {
      if (kind === 'videos') {
        const song = await getAdminSong(owner!.id, signal)
        return {
          items: song.videos.map((v) => ({
            id: v.id,
            name: v.sourceTitle ?? v.youtubeId,
          })),
          hasNext: false,
        }
      }
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (kind === 'reviews') {
        params.set('status', status)
        if (disposition) params.set('disposition', disposition)
      } else params.set('q', q)
      const raw = await apiClient.request<unknown>(`/admin/${kind}?${params}`, {
        signal,
        cache: 'no-store',
      })
      signal.throwIfAborted()
      const p = pageSchema.safeParse(raw)
      if (!p.success || p.data.page !== page || p.data.size !== 20)
        throw new ApiResponseError()
      return {
        items: p.data.items.map((x) => ({
          id: x.id,
          name: x.name ?? x.title ?? '제목 없음',
        })),
        hasNext: p.data.hasNext,
      }
    },
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  if (isAdminAccessError(query.error))
    return <AdminApiAccess error={query.error} user={user} />
  return (
    <>
      {kind === 'reviews' ? (
        <>
          <Choices
            label="검토 상태"
            value={status}
            values={[
              ['ALL', '전체'],
              ['PENDING', '검토 대기'],
              ['IGNORED', '제외'],
              ['REGISTERED', '등록 완료'],
            ]}
            onChange={(v) => {
              setStatus(v)
              setPage(0)
            }}
          />
          <Choices
            label="분류 결과"
            value={disposition}
            values={[
              ['', '전체'],
              ['REVIEW', '검토 대상'],
              ['EXCLUDED', '분류 제외'],
              ['DEFERRED', '수집 분류 유예'],
            ]}
            onChange={(v) => {
              setDisposition(v)
              setPage(0)
            }}
          />
        </>
      ) : kind === 'videos' ? (
        <p>
          선택한 곡에 연결된 영상 · 이력 조회용 선택이며 연결 값은 바뀌지
          않습니다.
        </p>
      ) : (
        <form
          className="grid gap-4"
          onSubmit={(e) => {
            e.preventDefault()
            setQ(input.trim())
            setPage(0)
          }}
        >
          <TextField
            label={
              kind === 'songs'
                ? '곡 제목·검색 별칭'
                : kind === 'works'
                  ? '원곡 제목·별칭'
                  : kind === 'channels'
                    ? '채널 이름'
                    : `${auditKinds[kind]} 이름·별칭`
            }
            value={input}
            maxLength={200}
            onChange={(e) => setInput(e.target.value)}
          />
          <Button variant="primary" type="submit">
            검색
          </Button>
        </form>
      )}
      {query.isPending ? (
        <SongLoading />
      ) : query.isError ? (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">목록을 조회하지 못했습니다</h2>
          <p>검색어·페이지와 선택 문맥을 유지했습니다.</p>
          <Button onClick={() => void query.refetch()}>다시 조회</Button>
        </section>
      ) : query.data.items.length ? (
        query.data.items.map((item) => (
          <section key={item.id} className="admin-panel">
            <h2 className="text-lg font-bold">{item.name}</h2>
            <Button onClick={() => onSelect(item)}>이 대상 선택</Button>
          </section>
        ))
      ) : (
        <section className="admin-panel" role="status">
          <h2 className="text-lg font-bold">조건에 맞는 대상이 없습니다</h2>
          <p>검색어·필터를 바꾸어 다시 찾아주세요.</p>
        </section>
      )}
      <AdminPagination
        page={page}
        hasNext={query.data?.hasNext ?? false}
        pending={query.isFetching}
        onPage={setPage}
      />
    </>
  )
}
