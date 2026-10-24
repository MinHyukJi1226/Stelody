import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getWithdrawalResult } from '@/features/account/api'
import {
  clearWithdrawalReceipt,
  type StoredWithdrawal,
} from '@/features/account/withdrawal-receipt'
import { ApiError } from '@/shared/api/errors'
import { WithdrawalPanel } from './withdrawal-panel'

export function WithdrawalOutcome({
  receipt,
  onComplete,
  onRejected,
  initialView,
}: {
  receipt: StoredWithdrawal
  onComplete: (user: string) => Promise<void>
  onRejected: (code: string | null) => void
  initialView: 'unknown' | 'server' | null
}) {
  const [started, setStarted] = useState(initialView === null)
  const handled = useRef(false)
  const query = useQuery({
    queryKey: ['withdrawal-result', receipt.id],
    queryFn: ({ signal }) => getWithdrawalResult(receipt, signal),
    enabled: started,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchInterval: (q) =>
      !q.state.error && q.state.data?.status === 'PROCESSING'
        ? q.state.data.pollAfterSeconds! * 1000
        : false,
  })
  const data = query.data
  const expired = query.error instanceof ApiError && query.error.status === 410
  useEffect(() => {
    if (expired) clearWithdrawalReceipt()
    if (handled.current || query.isError || !data) return
    if (data.status === 'COMPLETED' || data.status === 'NOT_COMPLETED') {
      handled.current = true
      clearWithdrawalReceipt()
      if (data.status === 'COMPLETED') void onComplete(receipt.user)
      else onRejected(data.code)
    }
  }, [data, expired, query.isError, onComplete, onRejected, receipt.user])
  const unavailable =
    query.error instanceof ApiError &&
    [400, 404, 410].includes(query.error.status)
  return (
    <WithdrawalPanel
      view={
        !started
          ? (initialView ?? 'unknown')
          : query.isFetching ||
              (!query.isError && data?.status === 'PROCESSING')
            ? 'checkingOutcome'
            : query.isError
              ? unavailable
                ? 'unavailable'
                : 'server'
              : 'unknown'
      }
      busy={
        query.isFetching || (!query.isError && data?.status === 'PROCESSING')
      }
      onCheck={
        expired
          ? undefined
          : () => {
              if (started) void query.refetch()
              else setStarted(true)
            }
      }
    />
  )
}
