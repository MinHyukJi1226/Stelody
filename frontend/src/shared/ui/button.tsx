import type { ComponentProps } from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/shared/lib/utils'

const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-xl text-sm font-medium leading-[1.5] whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-60',
  {
    variants: {
      variant: {
        secondary: 'bg-surface text-ink',
        primary: 'bg-brand-fill text-on-accent',
        destructive: 'bg-rose text-error',
        night: 'bg-night text-on-accent',
      },
      size: {
        default: 'min-h-[53px] p-4',
        icon: 'size-11 border border-line',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'default' },
  },
)

type ButtonProps = ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & { asChild?: boolean }

export function Button({
  className,
  variant,
  size,
  asChild = false,
  type = 'button',
  ...props
}: ButtonProps) {
  const Component = asChild ? Slot : 'button'
  return (
    <Component
      {...(!asChild ? { type } : {})}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  )
}
