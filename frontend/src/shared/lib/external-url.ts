export function externalUrl(value: string): string | undefined {
  try {
    const url = new URL(value)
    if (url.protocol === 'https:' || url.protocol === 'http:') return url.href
  } catch {
    /* Unregistered or malformed links are not navigable. */
  }
  return undefined
}
