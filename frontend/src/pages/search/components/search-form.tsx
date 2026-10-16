import { useState, type FormEvent } from 'react'
import {
  hasSearchConditions,
  validateSearchCriteria,
  type SearchCriteria,
} from '@/features/songs/search-params'
import { Button } from '@/shared/ui/button'
import { Input, TextField } from '@/shared/ui/input'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/shared/ui/tabs'
import { MemberFilter } from './member-filter'

export function SearchForm({
  criteria,
  onSearch,
  onReset,
}: {
  criteria: SearchCriteria
  onSearch: (criteria: SearchCriteria) => void
  onReset: () => void
}) {
  const [draft, setDraft] = useState(criteria)
  const [errors, setErrors] = useState<Record<string, string>>({})
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!hasSearchConditions(draft)) return
    const result = validateSearchCriteria(draft)
    if (!result.success) {
      setErrors(
        Object.fromEntries(
          result.error.issues.map((issue) => [
            String(issue.path[0]),
            issue.message,
          ]),
        ),
      )
      const field = event.currentTarget.elements.namedItem(
        String(result.error.issues[0]?.path[0]),
      )
      if (field instanceof HTMLElement) field.focus()
      return
    }
    setErrors({})
    onSearch({
      ...draft,
      q: draft.q.trim(),
      sort:
        Boolean(criteria.q) !== Boolean(draft.q.trim())
          ? draft.q.trim()
            ? 'RELEVANCE'
            : 'LATEST'
          : draft.sort,
    })
  }
  return (
    <form
      role="search"
      aria-label="노래 검색 조건"
      onSubmit={submit}
      className="flex min-w-0 flex-col gap-8"
    >
      <div className="flex min-w-0 items-center gap-2">
        <Input
          aria-label="곡명, 원곡, 멤버 검색"
          type="search"
          value={draft.q}
          maxLength={200}
          onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          placeholder="곡명, 원곡, 멤버 검색"
          className="flex-1"
        />
        <Button
          onClick={() => {
            setDraft({ q: '', type: 'ALL', memberIds: [], sort: 'LATEST' })
            setErrors({})
            onReset()
          }}
        >
          초기화
        </Button>
      </div>
      <div className="rounded-2xl bg-surface p-4 md:p-6">
        <Tabs
          value={draft.type}
          onValueChange={(type) =>
            setDraft({ ...draft, type: type as SearchCriteria['type'] })
          }
        >
          <TabsList aria-label="곡 유형">
            <TabsTrigger value="ALL">전체</TabsTrigger>
            <TabsTrigger value="ORIGINAL">오리지널</TabsTrigger>
            <TabsTrigger value="COVER">커버</TabsTrigger>
          </TabsList>
          <TabsContent value={draft.type} className="mt-4">
            <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2">
              <MemberFilter
                selected={draft.memberIds}
                onChange={(memberIds) => setDraft({ ...draft, memberIds })}
              />
              <div className="grid min-w-0 grid-cols-2 gap-2">
                <TextField
                  id="search-startDate"
                  name="startDate"
                  label="시작일"
                  type="date"
                  min="1900-01-01"
                  max="2100-12-31"
                  value={draft.startDate ?? ''}
                  onChange={(event) => {
                    setDraft({ ...draft, startDate: event.target.value })
                    setErrors({})
                  }}
                  {...(errors.startDate ? { error: errors.startDate } : {})}
                  className="text-sm"
                />
                <TextField
                  id="search-endDate"
                  name="endDate"
                  label="종료일"
                  type="date"
                  min="1900-01-01"
                  max="2100-12-31"
                  value={draft.endDate ?? ''}
                  onChange={(event) => {
                    setDraft({ ...draft, endDate: event.target.value })
                    setErrors({})
                  }}
                  {...(errors.endDate ? { error: errors.endDate } : {})}
                  className="text-sm"
                />
              </div>
            </div>
          </TabsContent>
        </Tabs>
        <Button
          type="submit"
          variant="primary"
          className="mt-4"
          disabled={!hasSearchConditions(draft)}
        >
          검색 결과 보기
        </Button>
      </div>
    </form>
  )
}
