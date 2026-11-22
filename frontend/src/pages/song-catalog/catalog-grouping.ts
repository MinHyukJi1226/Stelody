import type { CatalogGrouping } from './catalog-api'

const key = 'stelody-catalog-grouping'

export function isCatalogGrouping(value: unknown): value is CatalogGrouping {
  return value === 'TYPE' || value === 'MEMBER' || value === 'ORIGINAL_ARTIST'
}

export function readCatalogGrouping(): CatalogGrouping | null {
  try {
    const value = sessionStorage.getItem(key)
    return isCatalogGrouping(value) ? value : null
  } catch {
    return null
  }
}

export function rememberCatalogGrouping(value: CatalogGrouping | null) {
  try {
    if (value === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, value)
  } catch {
    // The current history entry still holds the selection if storage is blocked.
  }
}
