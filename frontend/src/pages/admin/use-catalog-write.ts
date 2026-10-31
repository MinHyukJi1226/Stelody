import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { sessionOptions } from '@/features/auth/session'
import { adminKeys } from '@/features/admin/queries'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError } from '@/features/admin/format'
import { ApiError } from '@/shared/api/errors'

export type CatalogGuard = {
  user: string
  conflictCodes?: readonly string[]
  onAccessError: (error: unknown) => void
}
export type EditGuard = { dirty: boolean; busy: boolean; uncertain: boolean }
export const cleanGuard: EditGuard = {
  dirty: false,
  busy: false,
  uncertain: false,
}

export function useCatalogWrite<T>(initial: T, guard: CatalogGuard) {
  const client = useQueryClient()
  const [base, setBase] = useState(initial)
  const [latest, setLatest] = useState<T | null>(null)
  const [mode, setMode] = useState<
    'edit' | 'confirm' | 'conflict' | 'unknown' | 'checked'
  >('edit')
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState('')
  const ref = useRef({
    mounted: false,
    busy: false,
    abort: null as AbortController | null,
  })
  const active = useCallback(() => {
    const user = client.getQueryData(sessionOptions.queryKey)
    return (
      ref.current.mounted &&
      user?.id === guard.user &&
      isActiveAdmin(user) &&
      !client
        .getQueryCache()
        .findAll({ queryKey: adminKeys(guard.user) })
        .some((x) => isAdminAccessError(x.state.error))
    )
  }, [client, guard.user])
  const verified = useCallback(
    () =>
      active() &&
      client.getQueryState(sessionOptions.queryKey)?.status === 'success',
    [active, client],
  )
  useEffect(() => {
    const r = ref.current
    r.mounted = true
    const unsubscribe = client.getQueryCache().subscribe(() => {
      if (!verified()) r.abort?.abort()
    })
    return () => {
      r.mounted = false
      r.abort?.abort()
      unsubscribe()
    }
  }, [client, verified])
  async function save(
    write: (signal: AbortSignal) => Promise<T>,
    onSaved: (value: T) => void,
  ) {
    if (ref.current.busy || !verified()) return
    ref.current.busy = true
    setBusy(true)
    setErrors({})
    setNotice('')
    const abort = new AbortController()
    ref.current.abort = abort
    try {
      const result = await write(abort.signal)
      if (!active()) return
      if (!verified()) throw new Error('SESSION_CHECK_INTERRUPTED')
      setBase(result)
      setLatest(null)
      setUncertain(false)
      setMode('edit')
      await client.invalidateQueries({
        queryKey: adminKeys(guard.user),
        refetchType: 'none',
      })
      if (!verified()) throw new Error('SESSION_CHECK_INTERRUPTED')
      onSaved(result)
    } catch (error) {
      if (!active()) return
      if (isAdminAccessError(error)) {
        guard.onAccessError(error)
        return
      }
      if (
        !abort.signal.aborted &&
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500
      ) {
        setMode(
          error.status === 409 &&
            (guard.conflictCodes ?? ['CATALOG_VERSION_CONFLICT']).includes(
              error.code,
            )
            ? 'conflict'
            : 'edit',
        )
        setErrors(
          Object.fromEntries(
            error.fieldErrors.map((x) => [x.field, x.message]),
          ),
        )
        setNotice(
          error.status === 404
            ? '대상을 찾을 수 없습니다. 입력은 보존되어 있습니다.'
            : error.message,
        )
      } else {
        setMode('unknown')
        setUncertain(true)
        setNotice('')
      }
    } finally {
      ref.current.busy = false
      ref.current.abort = null
      if (ref.current.mounted) setBusy(false)
    }
  }
  async function check(read: (signal: AbortSignal) => Promise<T>) {
    if (ref.current.busy || !verified()) return
    ref.current.busy = true
    setBusy(true)
    setNotice('')
    const abort = new AbortController()
    ref.current.abort = abort
    try {
      const value = await read(abort.signal)
      if (!verified() || abort.signal.aborted) return
      setLatest(value)
      if (mode === 'unknown') setMode('checked')
    } catch (error) {
      if (!active() || abort.signal.aborted) return
      if (isAdminAccessError(error)) guard.onAccessError(error)
      else
        setNotice(
          error instanceof ApiError && error.status === 404
            ? '대상을 찾을 수 없습니다. 입력은 보존되어 있습니다.'
            : '최신 저장값을 조회하지 못했습니다. 입력은 보존되어 있습니다.',
        )
    } finally {
      ref.current.busy = false
      ref.current.abort = null
      if (ref.current.mounted) setBusy(false)
    }
  }
  function review() {
    if (!latest || busy) return
    setBase(latest)
    setLatest(null)
    setMode('edit')
    setNotice('최신 정보 기준으로 내 입력을 다시 확인한 뒤 저장해주세요.')
  }
  return {
    verified,
    base,
    latest,
    mode,
    setMode,
    busy,
    uncertain,
    errors,
    setErrors,
    notice,
    setNotice,
    save,
    check,
    review,
  }
}
