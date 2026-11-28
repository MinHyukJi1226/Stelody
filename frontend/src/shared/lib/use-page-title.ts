import { useEffect } from 'react'
import { useLocation } from 'react-router'

export function usePageTitle(title: string | undefined) {
  const { pathname } = useLocation()
  useEffect(() => {
    if (title) document.title = title
  }, [pathname, title])
}
