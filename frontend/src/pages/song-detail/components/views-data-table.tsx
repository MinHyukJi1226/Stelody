import type { ViewRow } from '@/features/songs/view-series'
import { formatIncrease, formatViewCount } from '@/features/songs/view-series'
import { formatObservedAt } from '../format'

export function ViewsDataTable({
  rows,
  selected,
  onSelect,
  loading = false,
  error = false,
}: {
  rows: ViewRow[]
  selected: string | null
  onSelect: (date: string) => void
  loading?: boolean
  error?: boolean
}) {
  return (
    <div className="flex flex-col gap-4">
      <h3 className="text-lg font-bold">날짜별 데이터</h3>
      <div className="max-h-[465px] overflow-y-auto md:max-h-[395px]">
        <table className="w-full table-fixed text-left text-sm leading-[1.5]">
          <caption className="sr-only">날짜별 데이터</caption>
          <colgroup>
            <col className="w-[31%] md:w-[32%]" />
            <col className="w-[36%]" />
            <col className="w-[33%] md:w-[32%]" />
          </colgroup>
          <thead className="sticky top-0 z-10 bg-bg text-sm text-muted">
            <tr>
              <th scope="col" className="py-3 pr-1 font-medium">
                날짜(KST)
              </th>
              <th scope="col" className="py-3 pr-1 font-medium">
                누적 조회수
              </th>
              <th scope="col" className="py-3 font-medium">
                일별 증가량
              </th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              Array.from({ length: 7 }, (_, index) => (
                <tr
                  key={index}
                  aria-hidden="true"
                  className="h-14 border-b border-line md:h-11"
                >
                  {[0, 1, 2].map((cell) => (
                    <td key={cell} className="pr-4">
                      <div className="h-3.5 w-2/3 rounded bg-line" />
                    </td>
                  ))}
                </tr>
              ))
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={3} className="py-4 text-xs text-muted">
                  {error
                    ? '데이터를 다시 불러오면 표도 갱신됩니다.'
                    : '표시할 날짜별 데이터가 없습니다.'}
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr
                  key={row.date}
                  className={`border-b border-line ${selected === row.date ? 'bg-tint' : ''}`}
                >
                  <th scope="row" className="break-words pr-1 font-normal">
                    <button
                      type="button"
                      className="flex min-h-14 w-full flex-col justify-center py-2 text-left text-sm focus-visible:outline-offset-[-2px] md:min-h-11"
                      aria-label={`${row.date} 데이터 보기`}
                      aria-pressed={selected === row.date}
                      onClick={() => onSelect(row.date)}
                    >
                      <time
                        dateTime={row.date}
                        title={row.date}
                        className={row.isToday ? 'text-warning' : ''}
                      >
                        {row.date.slice(5).replace('-', '.')}
                      </time>
                      {row.isToday && (
                        <>
                          <span className="text-[10px] text-warning md:text-xs">
                            오늘 · 집계 중
                          </span>
                          <span className="text-[10px] text-warning md:text-xs">
                            {row.observedAt
                              ? `${formatObservedAt(row.observedAt).match(/\d{2}:\d{2}/)?.[0] ?? ''} 관측`
                              : '관측 없음'}
                          </span>
                        </>
                      )}
                    </button>
                  </th>
                  <td className="break-words py-2 pr-1 text-sm tabular-nums">
                    {formatViewCount(row.viewCount)}
                  </td>
                  <td
                    className={`break-words py-2 text-sm tabular-nums ${row.increase === null ? 'text-muted' : ''}`}
                  >
                    {formatIncrease(row)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
