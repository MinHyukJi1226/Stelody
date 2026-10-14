import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  applyTheme,
  readThemePreference,
  saveThemePreference,
  systemTheme,
  themeStorageKey,
  type Theme,
} from '@/shared/lib/theme'

export function useTheme() {
  const preference = useRef(readThemePreference())
  const [theme, setTheme] = useState<Theme>(
    () => readThemePreference() ?? systemTheme(),
  )

  useLayoutEffect(() => applyTheme(theme), [theme])

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const updateSystem = () => {
      if (!preference.current) setTheme(systemTheme())
    }
    const updateStorage = (event: StorageEvent) => {
      if (event.key !== themeStorageKey && event.key !== null) return
      preference.current = readThemePreference()
      setTheme(preference.current ?? systemTheme())
    }
    media.addEventListener('change', updateSystem)
    window.addEventListener('storage', updateStorage)
    return () => {
      media.removeEventListener('change', updateSystem)
      window.removeEventListener('storage', updateStorage)
    }
  }, [])

  function toggleTheme() {
    const next = theme === 'light' ? 'dark' : 'light'
    preference.current = next
    saveThemePreference(next)
    setTheme(next)
  }

  return { theme, toggleTheme }
}
