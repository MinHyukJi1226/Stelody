export type Theme = 'light' | 'dark'
export const themeStorageKey = 'stelody-theme'

export function readThemePreference(): Theme | null {
  try {
    const value = localStorage.getItem(themeStorageKey)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

export function systemTheme(): Theme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
}

export function initializeTheme() {
  applyTheme(readThemePreference() ?? systemTheme())
}

export function saveThemePreference(theme: Theme) {
  try {
    localStorage.setItem(themeStorageKey, theme)
  } catch {
    // Theme switching remains available when browser storage is blocked.
  }
}
