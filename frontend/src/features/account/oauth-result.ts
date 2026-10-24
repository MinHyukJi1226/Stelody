const reserved = new Set([
  'reauthresult',
  'reautherror',
  'code',
  'state',
  'error',
  'error_description',
  'access_token',
  'refresh_token',
  'id_token',
])
export type ReauthenticationResult = 'success' | 'cancelled' | 'failed' | null
const codes = new Set([
  'SESSION_EXPIRED',
  'REAUTHENTICATION_REQUEST_INVALID',
  'REAUTHENTICATION_REQUEST_EXPIRED',
  'REAUTHENTICATION_ACCOUNT_MISMATCH',
  'GOOGLE_REAUTHENTICATION_CANCELLED',
  'GOOGLE_REAUTHENTICATION_FAILED',
  'ACCOUNT_STORAGE_UNAVAILABLE',
])

export function readReauthenticationResult(
  params: URLSearchParams,
): ReauthenticationResult {
  if (!params.has('reauthResult') && !params.has('reauthError')) return null
  const result = params.get('reauthResult')
  if (
    params.getAll('reauthResult').length !== 1 ||
    params.getAll('reauthError').length > 1 ||
    (result === 'success' && params.has('reauthError'))
  )
    return 'failed'
  return result === 'success' || result === 'cancelled' ? result : 'failed'
}

export function readReauthenticationError(params: URLSearchParams) {
  const values = params.getAll('reauthError')
  return values.length === 1 && codes.has(values[0]!) ? values[0] : undefined
}

export function cleanReauthenticationReturn(params: URLSearchParams) {
  const clean = new URLSearchParams(params)
  for (const key of new Set(clean.keys()))
    if (reserved.has(key.toLowerCase())) clean.delete(key)
  return clean
}
