import { useSearchParams } from 'react-router'
import { readSearchParams } from '@/features/songs/search-params'
import { StatePanel } from '@/shared/ui/state-panel'
import { SearchResults } from './components/search-results'

export function SearchPage() {
  const [params] = useSearchParams()
  const { criteria, error, active } = readSearchParams(params)
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <div className="flex min-w-0 flex-1 flex-col gap-4 px-5 py-8 md:px-8">
        {error ? (
          <StatePanel title="검색 조건을 확인해 주세요" description={error} />
        ) : active && criteria ? (
          <SearchResults key={params.toString()} criteria={criteria} />
        ) : (
          <h1 className="home-section-enter text-lg font-bold leading-[1.5]">
            검색어를 입력해 주세요
          </h1>
        )}
      </div>
      <footer className="grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
