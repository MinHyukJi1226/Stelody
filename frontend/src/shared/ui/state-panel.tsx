import { Button } from './button'

export function StatePanel({
  title,
  description,
  onRetry,
}: {
  title: string
  description?: string
  onRetry?: () => void
}) {
  return (
    <div className="rounded-2xl bg-surface p-6" role="status">
      <p className="font-bold">{title}</p>
      {description && <p className="mt-2 text-sm text-muted">{description}</p>}
      {onRetry && (
        <Button variant="primary" className="mt-4" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}
