// Keep the same internal-path restrictions as the server's OAuth returnTo contract.
export function safeReturnPath(value: string | null | undefined): string {
  if (!value || value.length > 2048 || !/^\/(?!\/)/.test(value))
    return '/library'
  if (/\s|\\/.test(value) || hasControlCharacter(value)) return '/library'
  try {
    decodeURI(value) // Reject malformed percent encodings before constructing a browser URL.
    const rawPath = value.split(/[?#]/)[0]!
    const decoded = decodeURIComponent(rawPath)
    if (
      /%(2f|5c)/i.test(rawPath) ||
      /[%\\;\s]/.test(decoded) ||
      hasControlCharacter(decoded) ||
      decoded.split('/').some((p) => p === '.' || p === '..') ||
      /^\/(api|actuator|login)(\/|$)/i.test(decoded)
    )
      return '/library'
    const url = new URL(value, 'https://stelody.invalid')
    if (url.origin !== 'https://stelody.invalid') return '/library'
    url.searchParams.delete('loginResult')
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return '/library'
  }
}

const loginReturnKey = 'stelody-login-return'

export function rememberLoginReturn(returnTo: string) {
  try {
    sessionStorage.setItem(loginReturnKey, safeReturnPath(returnTo))
  } catch {
    // Storage is optional; do not reuse an older destination if writing failed.
    clearLoginReturn()
  }
}

export function readLoginReturn() {
  try {
    return safeReturnPath(sessionStorage.getItem(loginReturnKey))
  } catch {
    return '/library'
  }
}

export function clearLoginReturn() {
  try {
    sessionStorage.removeItem(loginReturnKey)
  } catch {
    /* Login remains available when browser storage is blocked. */
  }
}

// Call only when navigation is actually requested, never while rendering a link.
export function beginLogin(returnTo: string) {
  rememberLoginReturn(returnTo)
  return '/login'
}

function hasControlCharacter(value: string) {
  return Array.from(value).some((character) => {
    const code = character.charCodeAt(0)
    return code < 32 || (code >= 127 && code <= 159)
  })
}
