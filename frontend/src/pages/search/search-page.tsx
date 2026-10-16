import { useSearchParams } from 'react-router'
import {
  readSearchParams,
  searchParams,
  type SearchCriteria,
} from '@/features/songs/search-params'
import { SiteFooter } from '@/shared/ui/site-footer'
import { StatePanel } from '@/shared/ui/state-panel'
import { SearchForm } from './components/search-form'
import { SearchResults } from './components/search-results'

export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const { criteria, error, active } = readSearchParams(params)
  const initial: SearchCriteria = criteria ?? {
    q: params.get('q') ?? '',
    type: 'ALL',
    memberIds: [],
    sort: 'LATEST',
  }
  function search(next: SearchCriteria) {
    setParams(searchParams(next))
  }
  return (
    <div className="flex min-w-0 flex-col gap-8">
      <header className="flex flex-col gap-8">
        <p className="text-xs leading-[1.5] text-brand">SEARCH</p>
        <h1 className="text-[32px] font-bold leading-[1.5]">노래 검색</h1>
        <p className="text-base leading-6 text-muted">
          곡명 · 원곡 · 멤버를 한 곳에서 검색하고 조건을 좁혀보세요.
        </p>
      </header>
      <SearchForm
        key={params.toString()}
        criteria={initial}
        onSearch={search}
        onReset={() => setParams({})}
      />
      {error ? (
        <StatePanel title="검색 조건을 확인해 주세요" description={error} />
      ) : (
        active &&
        criteria && (
          <SearchResults
            criteria={criteria}
            onSort={(sort) =>
              setParams(searchParams({ ...criteria, sort }), {
                preventScrollReset: true,
              })
            }
          />
        )
      )}
      <SiteFooter />
    </div>
  )
}
