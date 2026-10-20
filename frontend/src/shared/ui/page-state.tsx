import { Link } from 'react-router'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'

export function PageState({
  title,
  description,
  kind,
  detail,
  onRetry,
  action,
}: {
  title: string
  description: string
  kind: 'empty' | 'error' | 'unavailable'
  detail?: string
  onRetry?: () => void
  action: { to: string; label: string }
}) {
  return (
    <section
      role="status"
      className="mx-auto flex w-full max-w-[640px] flex-col items-start gap-5 rounded-2xl bg-surface p-6 md:p-10"
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-14 items-center justify-center rounded-2xl text-2xl font-bold',
          kind === 'empty'
            ? 'bg-tint text-brand'
            : kind === 'error'
              ? 'bg-rose text-error'
              : 'bg-amber text-warning',
        )}
      >
        {kind === 'empty' ? '✦' : kind === 'error' ? '!' : '—'}
      </span>
      <h2 className="break-words text-2xl font-bold leading-[1.5]">{title}</h2>
      <p className="text-base leading-[1.5] text-muted">{description}</p>
      {detail && (
        <p className="w-full break-words rounded-lg bg-bg p-4 text-sm text-muted">
          {detail}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {onRetry ? (
          <Button variant="primary" onClick={onRetry}>
            다시 시도
          </Button>
        ) : (
          <Button asChild variant="primary">
            <Link to={action.to}>{action.label}</Link>
          </Button>
        )}
        <Button asChild>
          <Link to="/">홈으로</Link>
        </Button>
      </div>
    </section>
  )
}
