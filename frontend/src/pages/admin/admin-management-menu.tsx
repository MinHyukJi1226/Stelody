import { AuditButton } from './audit-button'
import {
  auditRecoveryKey,
  readAuditRecovery,
} from '@/features/admin/audit-recovery'
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAdminUser } from '@/features/admin/admin-context'
import { songListOptions, songVideoPath } from '@/features/admin/songs'
import { isAdminAccessError } from '@/features/admin/format'
import { TextField } from '@/shared/ui/input'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { AdminApiAccess, AdminPagination } from './admin-shared'

/** AD01-menu: expose implemented destinations; retain the other designs as 준비 중. */
export function AdminManagementMenu() {
  const location = useLocation()
  const user = useAdminUser()
  const [open, setOpen] = useState(
    () =>
      !!readAuditRecovery(
        auditRecoveryKey(user, location.pathname + location.search),
      ),
  )
  const [chooseSong, setChooseSong] = useState(false)
  const navigate = useNavigate()
  const returnTo =
    location.pathname === '/admin/songs'
      ? location.pathname + location.search
      : '/admin/songs'
  const items: readonly (readonly [string, string])[] = [
    ['노래 수동 등록', `/admin/songs/new?${new URLSearchParams({ returnTo })}`],
    ['영상 추가 연결', 'select-song'],
    ['원곡 관리', '/admin/works'],
    ['멤버 관리', '/admin/members'],
    ['공식 채널 관리', '/admin/channels'],
    ['수집 분류 규칙 관리', '/admin/collection-rules'],
    ['개별 후보 검토', '/admin/review-items'],
    ['실패 작업 재시도', '/admin/failures'],
  ]
  return (
    <>
      <Button onClick={() => setOpen(true)}>관리 기능</Button>
      {open && (
        <Dialog
          labelledBy="admin-management-title"
          onDismiss={() => setOpen(false)}
          className="admin-management-menu"
        >
          <div className="grid gap-2">
            <h2 id="admin-management-title" className="text-lg font-bold">
              관리 기능
            </h2>
            <p className="text-sm text-muted">
              필요한 관리 화면을 선택해 주세요.
            </p>
          </div>
          <div className="admin-management-scroll">
            <AuditButton />
            <div className="grid gap-2 sm:grid-cols-2">
              {items.map(([label, to]) =>
                to === 'select-song' ? (
                  <Button
                    key={label}
                    onClick={() => {
                      setOpen(false)
                      setChooseSong(true)
                    }}
                  >
                    {label}
                  </Button>
                ) : (
                  <Button asChild key={label}>
                    <Link to={to} onClick={() => setOpen(false)}>
                      {label}
                    </Link>
                  </Button>
                ),
              )}
            </div>
            <div className="grid gap-2">
              <p className="text-sm text-muted">준비 중</p>
              {[
                '커버 자동 공개 설정',
                '특별 목적 후보 처리',
                '최근 24시간 급상승',
              ].map((label) => (
                <Button disabled key={label}>
                  {label} · 정책 보류
                </Button>
              ))}
            </div>
          </div>
          <Button onClick={() => setOpen(false)}>닫기</Button>
        </Dialog>
      )}
      {chooseSong && (
        <VideoTargetPicker
          onDismiss={() => setChooseSong(false)}
          onApply={(id) => {
            setChooseSong(false)
            navigate(songVideoPath(id, returnTo))
          }}
        />
      )}
    </>
  )
}

export function VideoTargetPicker({
  onDismiss,
  onApply,
}: {
  onDismiss: () => void
  onApply: (id: string) => void
}) {
  const user = useAdminUser()
  const [q, setQ] = useState(''),
    [input, setInput] = useState(''),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState('')
  const query = useQuery(songListOptions(user, q, page, 20))
  return (
    <Dialog
      labelledBy="video-target-title"
      onDismiss={onDismiss}
      className="song-selector"
    >
      <h2 id="video-target-title" className="text-lg font-bold">
        기존 곡 선택
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
              label="곡 제목·검색 별칭"
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
            <div className="grid gap-3" role="alert">
              <p>곡 목록을 조회하지 못했습니다.</p>
              <Button onClick={() => void query.refetch()}>다시 조회</Button>
            </div>
          ) : query.data.items.length ? (
            query.data.items.map((song) => (
              <label key={song.id} className="song-select-row">
                <input
                  type="radio"
                  name="video-target"
                  checked={selected === song.id}
                  onChange={() => setSelected(song.id)}
                />
                <span>{song.name}</span>
              </label>
            ))
          ) : (
            <p>검색 결과가 없습니다.</p>
          )}
          <AdminPagination
            page={page}
            hasNext={query.data?.hasNext ?? false}
            pending={query.isFetching}
            onPage={setPage}
          />
          <Button
            variant="primary"
            disabled={!selected || query.isError}
            onClick={() => onApply(selected)}
          >
            선택 적용
          </Button>
        </>
      )}
      <Button onClick={onDismiss}>취소</Button>
    </Dialog>
  )
}
