import { useLocation } from 'react-router'
import { useAdminUser } from '@/features/admin/admin-context'
import {
  readAuditRecovery,
  saveAuditRecovery,
} from '@/features/admin/audit-recovery'
import { useContext, useState, type ReactNode } from 'react'
import { AuditGuardContext } from '@/features/admin/audit-context'
import type { AuditKind } from '@/features/admin/audit'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { AuditOverlay } from './admin-audit'
export function AuditScope({
  dirty,
  busy,
  uncertain,
  children,
}: {
  dirty: boolean
  busy: boolean
  uncertain: boolean
  children: ReactNode
}) {
  const parent = useContext(AuditGuardContext)
  return (
    <AuditGuardContext
      value={{
        dirty: parent.dirty || dirty,
        busy: parent.busy || busy,
        uncertain: parent.uncertain || uncertain,
      }}
    >
      {children}
    </AuditGuardContext>
  )
}
export function AuditButton({
  kind,
  id,
  label = '변경 이력',
  name,
  disabled = false,
}: {
  kind?: AuditKind
  id?: string | null
  label?: string
  name?: string
  disabled?: boolean
}) {
  const location = useLocation(),
    user = useAdminUser(),
    recoveryKey = JSON.stringify([
      'stelody:audit',
      user,
      location.pathname + location.search,
      kind ?? 'hub',
      id ?? '',
    ])
  const [recovery] = useState(() => readAuditRecovery(recoveryKey))
  const guard = useContext(AuditGuardContext),
    [open, setOpen] = useState(!!recovery),
    [confirm, setConfirm] = useState(false)
  function show() {
    saveAuditRecovery(recoveryKey, {
      target: kind ? { kind, id: id ?? null, name: name ?? '' } : null,
      page: 0,
      entryId: null,
    })
    setOpen(true)
  }
  return (
    <>
      <Button
        disabled={disabled || guard.busy}
        onClick={() => {
          if (guard.dirty || guard.uncertain) setConfirm(true)
          else show()
        }}
      >
        {label}
      </Button>
      {confirm && (
        <Dialog labelledBy="audit-preserve" onDismiss={() => setConfirm(false)}>
          <div className="grid gap-6">
            <h2 id="audit-preserve" className="text-lg font-bold">
              {guard.uncertain
                ? '저장 결과가 불확실합니다'
                : '작성 중인 입력이 있습니다'}
            </h2>
            <p>
              {guard.uncertain
                ? '이력 조회만으로 저장 성공을 확정하지 않습니다. 입력·요청 정보를 유지하고 기존 결과 확인 절차로 돌아옵니다.'
                : '입력을 유지한 채 읽기 전용 이력을 열 수 있습니다. 조회는 저장을 수행하지 않습니다.'}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setConfirm(false)}>계속 작성</Button>
              <Button
                variant="primary"
                onClick={() => {
                  setConfirm(false)
                  show()
                }}
              >
                입력을 유지하고 이력 열기
              </Button>
            </div>
          </div>
        </Dialog>
      )}
      {open && (
        <AuditOverlay
          recovery={recovery}
          onView={(value) => saveAuditRecovery(recoveryKey, value)}
          initial={kind ? { kind, id: id ?? null, name: name ?? '' } : null}
          onClose={() => {
            saveAuditRecovery(recoveryKey, null)
            setOpen(false)
          }}
        />
      )}
    </>
  )
}
