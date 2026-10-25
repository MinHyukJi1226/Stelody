import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '@/shared/ui/button'
import { adminPaths } from '@/features/admin/api'

export function SongNav() {
  return (
    <nav className="flex flex-wrap gap-2" aria-label="관리자 메뉴">
      {[
        [adminPaths.inbox, '작업함'],
        [adminPaths.songs, '노래 목록'],
        [adminPaths.collection, '수집 현황'],
      ].map(([to, label]) => (
        <Button asChild key={to}>
          <Link to={to!}>{label}</Link>
        </Button>
      ))}
    </nav>
  )
}
export function SongCard({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <section className="admin-panel song-edit-card">
      <h2 className="text-lg font-bold">{title}</h2>
      {children}
    </section>
  )
}
export function Hint({ children }: { children: ReactNode }) {
  return <p className="text-xs text-muted">{children}</p>
}
export function FieldError({ message }: { message?: string | undefined }) {
  return message ? (
    <p role="alert" className="text-xs text-error">
      {message}
    </p>
  ) : null
}
export function Choices<T extends string>({
  label,
  value,
  values,
  onChange,
  error,
}: {
  label: string
  value: T
  values: readonly (readonly [T, string])[]
  onChange: (value: T) => void
  error?: string | undefined
}) {
  return (
    <div className="grid gap-2">
      <span className="text-sm font-medium">{label}</span>
      <div className="flex flex-wrap gap-2" role="group" aria-label={label}>
        {values.map(([key, name]) => (
          <Button
            key={key}
            aria-pressed={value === key}
            variant={value === key ? 'primary' : 'secondary'}
            onClick={() => onChange(key)}
          >
            {name}
          </Button>
        ))}
      </div>
      <FieldError message={error} />
    </div>
  )
}
export function ReadValue({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <div className="grid gap-2">
      <span className="text-sm font-medium">{label}</span>
      <div className="song-read-value">{children}</div>
    </div>
  )
}
