import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Link } from 'react-router'
import privacyPolicy from '../../../legal/privacy-policy.md?raw'
import termsOfService from '../../../legal/terms-of-service.md?raw'
import './legal-page.css'

function LegalPage({ document }: { document: string }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col px-5 md:px-10">
      <article
        aria-labelledby="legal-title"
        className="legal-document mx-auto w-full max-w-6xl py-8 md:py-12"
      >
        <Markdown
          remarkPlugins={[remarkGfm]}
          components={{
            h1: ({ children }) => <h1 id="legal-title">{children}</h1>,
            a: ({ href, children }) => {
              const to =
                href === 'privacy-policy.md'
                  ? '/privacy'
                  : href === 'terms-of-service.md'
                    ? '/terms'
                    : undefined
              return to ? (
                <Link to={to}>{children}</Link>
              ) : (
                <a href={href}>{children}</a>
              )
            },
            table: ({ children }) => (
              <div
                className="legal-table-scroll"
                role="region"
                aria-label="정책 상세 표"
                tabIndex={0}
              >
                <table>{children}</table>
              </div>
            ),
          }}
        >
          {document}
        </Markdown>
      </article>
    </div>
  )
}

export function PrivacyPolicyPage() {
  return <LegalPage document={privacyPolicy} />
}

export function TermsOfServicePage() {
  return <LegalPage document={termsOfService} />
}
