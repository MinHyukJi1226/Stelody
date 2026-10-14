import { useId, type ComponentProps } from 'react'
import { cn } from '@/shared/lib/utils'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return (
    <input
      className={cn(
        'min-h-14 w-full min-w-0 rounded-xl border border-line bg-surface p-4 text-base leading-6 text-ink placeholder:text-muted disabled:cursor-not-allowed disabled:opacity-60 aria-invalid:border-error aria-invalid:text-error',
        className,
      )}
      {...props}
    />
  )
}

type TextFieldProps = ComponentProps<typeof Input> & {
  label: string
  description?: string
  error?: string
}

export function TextField({
  label,
  description,
  error,
  id,
  'aria-describedby': describedBy,
  ...props
}: TextFieldProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const hint = error || description
  const hintId = `${inputId}-hint`
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={inputId} className="text-sm font-medium leading-[1.5]">
        {label}
      </label>
      <Input
        {...props}
        id={inputId}
        aria-invalid={error ? true : props['aria-invalid']}
        aria-describedby={
          [describedBy, hint ? hintId : undefined].filter(Boolean).join(' ') ||
          undefined
        }
      />
      {hint && (
        <p
          id={hintId}
          className={cn(
            'text-xs leading-[1.5]',
            error ? 'text-error' : 'text-muted',
          )}
        >
          {hint}
        </p>
      )}
    </div>
  )
}
