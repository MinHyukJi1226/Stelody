import { z } from 'zod'
import { receiptSchema, type WithdrawalReceipt } from './api'

const key = 'stelody-withdrawal-result'
const storedSchema = receiptSchema.extend({ user: z.string().min(1) })
export type StoredWithdrawal = WithdrawalReceipt & { user: string }
export type ReceiptState =
  | { kind: 'none' }
  | { kind: 'unavailable' }
  | { kind: 'expired' }
  | { kind: 'ready'; receipt: StoredWithdrawal }

export function readWithdrawalReceipt(): ReceiptState {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return { kind: 'none' }
    const parsed = storedSchema.safeParse(JSON.parse(raw))
    if (!parsed.success) return { kind: 'unavailable' }
    if (Date.parse(parsed.data.expiresAt) <= Date.now()) {
      clearWithdrawalReceipt()
      return { kind: 'expired' }
    }
    return { kind: 'ready', receipt: parsed.data }
  } catch {
    return { kind: 'unavailable' }
  }
}

export function saveWithdrawalReceipt(receipt: StoredWithdrawal) {
  const raw = JSON.stringify(storedSchema.parse(receipt))
  sessionStorage.setItem(key, raw)
  if (sessionStorage.getItem(key) !== raw)
    throw new Error('결과 조회 권한 저장 실패')
}

export function clearWithdrawalReceipt() {
  try {
    sessionStorage.removeItem(key)
  } catch {
    /* Keep any remaining value for a later read-only check. */
  }
}
