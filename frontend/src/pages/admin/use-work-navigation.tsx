import { useCallback, useEffect, useRef, useState } from 'react'
import { useBeforeUnload, useBlocker, useNavigate } from 'react-router'
import { usePageActions, type PageAction } from '@/shared/lib/page-action-guard'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'

export function useWorkNavigation(
  dirty: boolean,
  busy: boolean,
  uncertain: boolean,
  returnTo: string,
  discardDescription = '저장하지 않은 선택·입력은 버려집니다. 이미 별도로 저장한 아티스트 정보는 유지됩니다.',
) {
  const navigate = useNavigate()
  const actions = usePageActions()
  const leaving = useRef(false)
  const [pending, setPending] = useState<PageAction | null>(null)
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !leaving.current &&
      (dirty || busy) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search ||
        currentLocation.hash !== nextLocation.hash),
  )
  useEffect(() => {
    // A click while saving is discarded rather than queued for execution after saving.
    if (busy && blocker.state === 'blocked') blocker.reset()
  }, [busy, blocker])
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (dirty || busy) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [dirty, busy],
    ),
  )
  useEffect(
    () =>
      actions?.register((action) => {
        if (busy) return true
        if (!leaving.current && dirty) {
          setPending(() => action)
          return true
        }
        action()
        return false
      }),
    [actions, dirty, busy],
  )
  const dismiss = () => {
    setPending(null)
    if (blocker.state === 'blocked') blocker.reset()
  }
  function cancel() {
    if (busy) return
    if (dirty) setPending(() => () => navigate(returnTo))
    else navigate(returnTo)
  }
  function leave() {
    if (busy) return
    leaving.current = true
    if (blocker.state === 'blocked') blocker.proceed()
    else pending?.()
  }
  const dialog =
    !busy && (pending || blocker.state === 'blocked') ? (
      <Dialog labelledBy="work-leave-title" onDismiss={dismiss}>
        <div className="grid gap-6">
          <h2 className="text-lg font-bold" id="work-leave-title">
            {uncertain
              ? '저장 결과를 확인하지 않고 이동할까요?'
              : '저장하지 않고 이동할까요?'}
          </h2>
          <p className="text-sm text-muted">
            {uncertain
              ? '이미 반영됐을 수 있습니다. 요청의 성공 여부를 확인하지 않고 나갑니다.'
              : discardDescription}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button onClick={dismiss}>계속 작성</Button>
            <Button variant="destructive" onClick={leave}>
              변경 버리고 이동
            </Button>
          </div>
        </div>
      </Dialog>
    ) : null
  return {
    cancel,
    dialog,
    go: (to: string) => {
      leaving.current = true
      navigate(to)
    },
    request: (to: string) => {
      if (!busy) setPending(() => () => navigate(to))
    },
  }
}
