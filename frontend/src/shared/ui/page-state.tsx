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
  heading = 'h2',
  showHome = true,
}: {
  title: string
  description?: string
  kind: 'empty' | 'error' | 'unavailable'
  detail?: string
  onRetry?: () => void
  action: { to: string; label: string; onClick?: () => void }
  heading?: 'h1' | 'h2'
  showHome?: boolean
}) {
  const Heading = heading
  return (
    <section
      role="status"
      className="mx-auto flex w-full min-w-0 max-w-[640px] flex-col items-start gap-5 rounded-2xl bg-surface p-6 md:p-10"
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
      <Heading className="w-full break-keep text-2xl font-bold leading-[1.5] [overflow-wrap:anywhere]">
        {title}
      </Heading>
      {description && (
        <p className="w-full break-keep text-base leading-[1.5] text-muted [overflow-wrap:anywhere]">
          {description}
        </p>
      )}
      {detail && (
        <p className="w-full break-keep rounded-lg bg-bg p-4 text-sm leading-[1.5] text-muted [overflow-wrap:anywhere]">
          {detail}
        </p>
      )}
      <div className="flex w-full min-w-0 flex-wrap gap-2 [&>*]:max-w-full [&>*]:whitespace-normal">
        {onRetry ? (
          <Button variant="primary" onClick={onRetry}>
            다시 시도
          </Button>
        ) : (
          <Button asChild variant="primary">
            <Link to={action.to} onClick={action.onClick}>
              {action.label}
            </Link>
          </Button>
        )}
        {showHome && (!!onRetry || action.to !== '/') && (
          <Button asChild>
            <Link to="/">홈으로</Link>
          </Button>
        )}
      </div>
    </section>
  )
}
