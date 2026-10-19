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

export function loginPath(returnTo: string) {
  return `/login?${new URLSearchParams({ returnTo: safeReturnPath(returnTo) })}`
}

export function oauthReturnPath(returnTo: string) {
  const path = loginPath(returnTo)
  // The nested path must also fit the server's 2,048-character limit.
  return path.length <= 2048 ? path : loginPath('/library')
}

function hasControlCharacter(value: string) {
  return Array.from(value).some((character) => {
    const code = character.charCodeAt(0)
    return code < 32 || (code >= 127 && code <= 159)
  })
}
