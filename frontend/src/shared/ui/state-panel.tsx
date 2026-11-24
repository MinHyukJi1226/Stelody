import { Button } from './button'

export function StatePanel({
  title,
  description,
  onRetry,
  busy = false,
}: {
  title: string
  description?: string
  onRetry?: () => void
  busy?: boolean
}) {
  return (
    <div
      className="mx-auto flex w-full min-w-0 max-w-[460px] flex-col items-center gap-3 rounded-2xl border border-line bg-surface px-[18px] py-[25px] text-center"
      role="status"
      aria-label={busy ? title : undefined}
      aria-busy={busy || undefined}
    >
      <p className="w-full break-keep text-base font-bold leading-[1.5] [overflow-wrap:anywhere] md:text-xl md:leading-[1.2]">
        {title}
      </p>
      {description && (
        <p className="w-full break-keep text-sm leading-[1.5] text-muted [overflow-wrap:anywhere]">
          {description}
        </p>
      )}
      {onRetry && (
        <Button
          variant="primary"
          className="min-h-11 min-w-[100px] max-w-full rounded-lg px-4 py-2 font-bold whitespace-normal"
          onClick={onRetry}
        >
          다시 시도
        </Button>
      )}
    </div>
  )
}
