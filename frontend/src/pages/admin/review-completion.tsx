import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { completeReviews, getReviewCompletion } from '@/features/admin/api'
import { useAdminUser } from '@/features/admin/admin-context'
import { adminKeys } from '@/features/admin/queries'
import { isAdminAccessError } from '@/features/admin/format'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { AdminApiAccess } from './admin-shared'

export function ReviewCompletionButton({ disabled }: { disabled: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button disabled={disabled} onClick={() => setOpen(true)}>
        검토 완료
      </Button>
      {open && <CompletionDialog onClose={() => setOpen(false)} />}
    </>
  )
}

function CompletionDialog({ onClose }: { onClose: () => void }) {
  const user = useAdminUser()
  const client = useQueryClient()
  const notify = useToast()
  const mounted = useRef(false)
  const busyRef = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const preview = useQuery({
    queryKey: [...adminKeys(user), 'review-completion'],
    queryFn: ({ signal }) => getReviewCompletion(signal),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const conflict = error instanceof ApiError && error.status === 409
  const access = isAdminAccessError(error) ? error : preview.error

  async function complete() {
    if (busyRef.current || !preview.data || conflict) return
    busyRef.current = true
    setBusy(true)
    setError(null)
    try {
      // Retrying after a lost response uses this same snapshot, never a new batch.
      const result = await completeReviews(preview.data)
      await client.invalidateQueries({
        queryKey: adminKeys(user),
        predicate: (query) => query.queryKey.at(-1) !== 'review-completion',
      })
      if (mounted.current) {
        notify(
          `검토를 완료했어요. 미등록 후보 ${result.deletedCount.toLocaleString()}건을 삭제했어요.`,
        )
        onClose()
      }
    } catch (cause) {
      if (mounted.current) setError(cause)
    } finally {
      busyRef.current = false
      if (mounted.current) setBusy(false)
    }
  }

  return (
    <Dialog
      labelledBy="review-completion-title"
      onDismiss={onClose}
      busy={busy}
    >
      <h2 id="review-completion-title" className="text-xl font-bold">
        검토 완료
      </h2>
      {isAdminAccessError(access) ? (
        <AdminApiAccess error={access} user={user} />
      ) : (
        <div className="my-4 space-y-3 text-sm">
          {preview.isPending ? (
            <p role="status">정리할 후보를 확인하고 있어요.</p>
          ) : preview.isError ? (
            <>
              <p role="alert">정리할 후보를 확인하지 못했어요.</p>
              <Button onClick={() => void preview.refetch()}>다시 확인</Button>
            </>
          ) : (
            <>
              <p>
                등록할 곡을 모두 등록했나요? 현재 남은 신규 검토 후보{' '}
                <strong>{preview.data.items.length.toLocaleString()}건</strong>
                을 완전히 삭제합니다. 삭제한 후보는 되돌릴 수 없습니다.
              </p>
              <p>등록된 곡과 이 창을 연 뒤 새로 들어온 후보는 유지됩니다.</p>
              {preview.data.hasMore && (
                <p>
                  한 번에 10,000건까지 정리합니다. 남은 후보는 다음 검토
                  완료에서 정리해 주세요.
                </p>
              )}
              {error != null && (
                <p role="alert">
                  {conflict
                    ? '후보가 변경되어 삭제하지 않았어요. 대상을 다시 확인해 주세요.'
                    : '삭제 결과를 확인하지 못했어요. 같은 대상으로 다시 요청할 수 있어요.'}
                </p>
              )}
              {conflict && (
                <Button
                  disabled={preview.isFetching}
                  onClick={async () => {
                    const refreshed = await preview.refetch()
                    if (refreshed.isSuccess) setError(null)
                  }}
                >
                  대상 다시 확인
                </Button>
              )}
            </>
          )}
        </div>
      )}
      <div className="mt-5 flex justify-end gap-2">
        <Button disabled={busy} onClick={onClose}>
          닫기
        </Button>
        <Button
          variant="primary"
          disabled={
            busy ||
            !preview.isSuccess ||
            !preview.data.items.length ||
            conflict ||
            isAdminAccessError(access)
          }
          onClick={() => void complete()}
        >
          {busy ? '정리 중…' : '미등록 후보 삭제'}
        </Button>
      </div>
    </Dialog>
  )
}
