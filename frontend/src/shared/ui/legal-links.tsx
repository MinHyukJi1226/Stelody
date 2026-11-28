import { Link } from 'react-router'
import { cn } from '@/shared/lib/utils'

export function LegalLinks({ className }: { className?: string }) {
  return (
    <nav
      aria-label="서비스 정책"
      className={cn('flex flex-wrap gap-x-4 gap-y-1', className)}
    >
      <Link className="py-2 underline-offset-4 hover:underline" to="/terms">
        이용약관
      </Link>
      <Link className="py-2 underline-offset-4 hover:underline" to="/privacy">
        개인정보처리방침
      </Link>
    </nav>
  )
}
