import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  entityOptions,
  type AdminSong,
  type EntityKind,
} from '@/features/admin/songs'
import { isAdminAccessError } from '@/features/admin/format'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import { AdminPagination, AdminTime } from './admin-shared'
import { songLabel } from '@/features/admin/format'
export type Selection = EntityKind | 'video'
const titles = {
  members: '참여 멤버 선택',
  artists: '외부 아티스트 선택',
  works: '연결할 원곡 선택',
  video: '대표 영상 선택',
}
type Props = {
  kind: Selection
  user: string
  selected: string[]
  videos: AdminSong['videos']
  onApply: (ids: string[]) => void
  onDismiss: () => void
  onAccessError: (error: unknown) => void
}
export function SongSelector(props: Props) {
  return (
    <Dialog
      labelledBy="song-selector-title"
      onDismiss={props.onDismiss}
      className="song-selector"
    >
      <h2 id="song-selector-title" className="text-lg font-bold">
        {titles[props.kind]}
      </h2>
      {props.kind === 'video' ? (
        <VideoSelector {...props} />
      ) : (
        <EntitySelector {...props} kind={props.kind} />
      )}
    </Dialog>
  )
}
function SelectionRows({
  rows,
  selected,
  onToggle,
  multiple,
}: {
  rows: { id: string; name: string; detail?: React.ReactNode }[]
  selected: string[]
  onToggle: (id: string) => void
  multiple: boolean
}) {
  return (
    <div className="grid gap-3">
      {rows.map((row) => (
        <label key={row.id} className="song-select-row">
          <input
            type={multiple ? 'checkbox' : 'radio'}
            name="song-selection"
            checked={selected.includes(row.id)}
            onChange={() => onToggle(row.id)}
          />
          <span className="min-w-0 break-words">
            {row.name}
            {row.detail && (
              <span className="block text-xs text-muted">{row.detail}</span>
            )}
          </span>
        </label>
      ))}
    </div>
  )
}
function EntitySelector(props: Props & { kind: EntityKind }) {
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState(props.selected)
  const query = useQuery(entityOptions(props.user, props.kind, q, page))
  const onAccessError = props.onAccessError
  useEffect(() => {
    if (isAdminAccessError(query.error)) onAccessError(query.error)
  }, [query.error, onAccessError])
  const multiple = props.kind !== 'works'
  return (
    <div className="grid gap-6">
      <form
        className="grid gap-3"
        onSubmit={(e) => {
          e.preventDefault()
          setQ(search)
          setPage(0)
        }}
      >
        <TextField
          label={
            props.kind === 'works' ? '원곡 제목·별칭 검색' : '이름·별칭 검색'
          }
          placeholder="검색어 입력"
          value={search}
          maxLength={200}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Button type="submit" disabled={query.isFetching}>
          검색
        </Button>
      </form>
      <p className="text-xs text-muted">
        {multiple
          ? '여러 항목을 선택할 수 있습니다.'
          : '기존 항목 하나를 선택합니다.'}{' '}
        선택은 최종 저장 전 초안에만 적용합니다.
      </p>
      {query.isError ? (
        <div role="status">
          <p>목록을 조회하지 못했어요.</p>
          <Button
            onClick={() => {
              if (isAdminAccessError(query.error))
                props.onAccessError(query.error)
              else void query.refetch()
            }}
          >
            {isAdminAccessError(query.error) ? '권한 확인' : '다시 조회'}
          </Button>
        </div>
      ) : query.isPending ? (
        <p role="status">불러오는 중…</p>
      ) : query.data.items.length === 0 ? (
        <p role="status">검색 결과가 없습니다.</p>
      ) : (
        <SelectionRows
          rows={query.data.items}
          multiple={multiple}
          selected={selected}
          onToggle={(id) =>
            setSelected((old) =>
              multiple
                ? old.includes(id)
                  ? old.filter((x) => x !== id)
                  : [...old, id]
                : [id],
            )
          }
        />
      )}
      <AdminPagination
        page={page}
        hasNext={query.data?.hasNext ?? false}
        pending={query.isFetching}
        onPage={setPage}
      />
      <div className="grid justify-items-start gap-2">
        <Button
          variant="primary"
          disabled={query.isPending || query.isError || selected.length > 100}
          onClick={() => props.onApply(selected)}
        >
          선택 적용
        </Button>
        <Button onClick={props.onDismiss}>취소</Button>
      </div>
    </div>
  )
}
function VideoSelector(props: Props) {
  const [selected, setSelected] = useState(props.selected)
  return (
    <div className="grid gap-6">
      <p className="text-xs text-muted">
        현재 곡에 연결된 영상에서 선택합니다. 공개 가능 여부는 저장 시 서버에서
        검증합니다.
      </p>
      <SelectionRows
        rows={props.videos.map((v) => ({
          id: v.id,
          name: v.sourceTitle ?? v.youtubeId,
          detail: (
            <>
              {songLabel(v.kind)} · {songLabel(v.availability)} ·{' '}
              <AdminTime value={v.publishedAt} empty="공개일 없음" />
            </>
          ),
        }))}
        selected={selected}
        multiple={false}
        onToggle={(id) => setSelected([id])}
      />
      {!props.videos.length && <p>연결된 영상이 없습니다.</p>}
      <div className="grid justify-items-start gap-2">
        <Button
          variant="primary"
          disabled={!selected.length}
          onClick={() => props.onApply(selected)}
        >
          선택 적용
        </Button>
        <Button onClick={props.onDismiss}>취소</Button>
      </div>
    </div>
  )
}
