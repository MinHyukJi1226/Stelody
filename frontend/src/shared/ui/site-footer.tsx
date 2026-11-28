import { LegalLinks } from '@/shared/ui/legal-links'
import type { Theme } from '@/shared/lib/theme'

export function SiteFooter({ theme }: { theme: Theme }) {
  return (
    <footer
      className={`grid shrink-0 gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10 ${theme === 'dark' ? 'bg-bg' : 'bg-surface'}`}
    >
      <p className="text-sm font-bold">Stelody · 스텔라이브 노래를 한 곳에서</p>
      <p>
        Stelody는 공식 서비스가 아닌 팬이 제작한 비영리·비공식 서비스입니다.
      </p>
      <p>
        문의 :{' '}
        <a href="mailto:nohalapan@gmail.com" className="hover:underline">
          nohalapan@gmail.com
        </a>
      </p>
      <LegalLinks />
    </footer>
  )
}
