import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { adminKeys } from '@/features/admin/queries'
import {
  getArtists,
  searchArtists,
  type Artist,
} from '@/features/admin/original-artists'
import { isAdminAccessError } from '@/features/admin/format'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import type { WorkArtistProps } from './work-artist-editor'
import { CatalogExit } from './catalog-exit'
import { cleanGuard } from './use-catalog-write'
import { ArtistEditor } from './original-artist-form'
import { Hint } from './song-ui'

export function OriginalArtistPicker(
  props: WorkArtistProps & {
    selected: string[]
    onApply: (rows: Artist[]) => void
    onArtistChange: (item: Artist, created: boolean) => void
  },
) {
  const [selection, setSelection] = useState(props.selected)
  const [input, setInput] = useState(''),
    [q, setQ] = useState(''),
    [page, setPage] = useState(0)
  const [known, setKnown] = useState<Record<string, Artist>>({})
  const [target, setTarget] = useState<Artist | 'new' | null>(null)
  const [exit, setExit] = useState(false),
    [notice, setNotice] = useState('')
  const [child, setChild] = useState(cleanGuard)
  const selected = useQuery({
    queryKey: [...adminKeys(props.user), 'artist-selection', props.selected],
    queryFn: ({ signal }) => getArtists(props.selected, signal),
    enabled: props.enabled,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  const query = useQuery({
    queryKey: [...adminKeys(props.user), 'artist-search', q, page],
    queryFn: ({ signal }) => searchArtists(q, page, signal),
    enabled: props.enabled && target === null,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
  const accessError = [query.error, selected.error].find(isAdminAccessError)
  const { onAccessError, onState } = props
  useEffect(() => {
    if (accessError) onAccessError(accessError)
  }, [accessError, onAccessError])
  const dirty = JSON.stringify(selection) !== JSON.stringify(props.selected)
  useEffect(() => {
    onState({
      dirty: dirty || child.dirty,
      busy: child.busy,
      uncertain: child.uncertain,
    })
  }, [dirty, child, onState])
  const records = Object.fromEntries(
    [
      ...(selected.data ?? []),
      ...(query.data?.items ?? []),
      ...Object.values(known),
    ]
      .sort((a, b) => a.version - b.version)
      .map((x) => [x.id, x]),
  )
  const rows = query.data?.items.map((x) => records[x.id]!) ?? []
  function cancel() {
    if (dirty) setExit(true)
    else props.onDismiss()
  }
  if (accessError) return null
  if (target)
    return (
      <ArtistEditor
        {...props}
        initial={target === 'new' ? null : target}
        onState={setChild}
        onDismiss={() => {
          setTarget(null)
          setChild(cleanGuard)
        }}
        onArtistSaved={(item, created) => {
          setKnown((old) => ({ ...old, [item.id]: item }))
          if (created && selection.length < 100)
            setSelection((old) => [...new Set([...old, item.id])])
          setNotice(
            created
              ? '아티스트를 등록했습니다. 아직 이 원곡에는 저장되지 않았습니다.'
              : '아티스트 정보를 저장했습니다. 원곡 연결 저장과는 별개입니다.',
          )
          setTarget(null)
          setChild(cleanGuard)
          props.onArtistChange(item, created)
          props.onSaved()
        }}
      />
    )
  if (!props.enabled || accessError) return null
  return (
    <Dialog
      labelledBy="original-artist-title"
      onDismiss={cancel}
      className="song-selector original-artist-dialog"
    >
      {exit ? (
        <CatalogExit
          onContinue={() => setExit(false)}
          onLeave={props.onDismiss}
        />
      ) : (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            원곡 아티스트 선택
          </h2>
          {notice && (
            <p className="song-read-value" role="status">
              {notice}
            </p>
          )}
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              setQ(input.trim())
              setPage(0)
            }}
          >
            <div className="min-w-0 flex-1">
              <TextField
                label="이름·검색 별칭"
                maxLength={200}
                value={input}
                onChange={(e) => setInput(e.target.value)}
              />
            </div>
            <Button type="submit" disabled={query.isFetching}>
              검색
            </Button>
          </form>
          <p className="text-sm text-muted">
            선택 {selection.length}명 · 여러 명 선택할 수 있습니다.
          </p>
          {query.isPending || selected.isPending ? (
            <div
              className="h-40 animate-pulse rounded-xl bg-bg"
              aria-label="아티스트 목록 불러오는 중"
            />
          ) : query.isError || selected.isError ? (
            <div className="grid gap-2">
              <p role="alert">
                아티스트 목록을 조회하지 못했어요. 선택은 유지됩니다.
              </p>
              <Button
                onClick={() => {
                  void query.refetch()
                  void selected.refetch()
                }}
              >
                다시 조회
              </Button>
            </div>
          ) : (
            <>
              <div className="grid gap-2">
                {rows.length ? (
                  rows.map((row) => (
                    <div
                      className="song-read-value flex items-start gap-3"
                      key={row.id}
                    >
                      <label className="flex min-w-0 flex-1 items-start gap-2">
                        <input
                          className="mt-1"
                          type="checkbox"
                          checked={selection.includes(row.id)}
                          disabled={
                            !selection.includes(row.id) &&
                            selection.length >= 100
                          }
                          onChange={(e) => {
                            if (e.target.checked) {
                              setKnown((old) => ({ ...old, [row.id]: row }))
                              setSelection((old) => [...old, row.id])
                            } else
                              setSelection((old) =>
                                old.filter((x) => x !== row.id),
                              )
                          }}
                        />
                        <span className="min-w-0 break-words">
                          <strong>{row.name}</strong>
                          <span className="mt-1 block text-xs text-muted">
                            별칭 · {row.aliases.join(' · ') || '없음'}
                          </span>
                        </span>
                      </label>
                      <Button
                        aria-label={`${row.name} 수정`}
                        onClick={() => setTarget(row)}
                      >
                        수정
                      </Button>
                    </div>
                  ))
                ) : (
                  <p className="text-sm text-muted">
                    검색 결과가 없습니다. 새 아티스트를 등록할 수 있습니다.
                  </p>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  disabled={page === 0 || query.isFetching}
                  onClick={() => setPage((old) => old - 1)}
                >
                  이전
                </Button>
                <span className="text-sm">{page + 1}페이지</span>
                <Button
                  disabled={!query.data?.hasNext || query.isFetching}
                  onClick={() => setPage((old) => old + 1)}
                >
                  다음
                </Button>
              </div>
            </>
          )}
          <Button onClick={() => setTarget('new')}>새 아티스트 등록</Button>
          {selection.length >= 100 && (
            <Hint>최대 100명까지 선택할 수 있습니다.</Hint>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              variant="primary"
              disabled={
                selection.some((id) => !records[id]) || selected.isError
              }
              onClick={() => props.onApply(selection.map((id) => records[id]!))}
            >
              선택 적용
            </Button>
            <Button onClick={cancel}>취소</Button>
          </div>
          <Hint>선택 적용만으로 원곡이 저장되지는 않습니다.</Hint>
        </>
      )}
    </Dialog>
  )
}
