import { useState } from 'react'
import type { SongDetail } from '@/features/songs/detail-queries'
import { Button } from '@/shared/ui/button'

export function SearchHelp({ detail }: { detail: SongDetail }) {
  const [notice, setNotice] = useState('')
  const [copying, setCopying] = useState(false)
  const recommended =
    detail.searchHelp.recommendedQuery?.trim() ||
    [detail.song.participants[0]?.name, detail.song.title]
      .filter(Boolean)
      .join(' ')
  async function copy() {
    setCopying(true)
    try {
      await navigator.clipboard.writeText(recommended)
      setNotice('검색어를 복사했습니다.')
    } catch {
      setNotice('복사하지 못했습니다. 권장 검색어를 직접 선택해 복사해 주세요.')
    } finally {
      setCopying(false)
    }
  }
  return (
    <section aria-label="YouTube 검색 도움말" className="flex flex-col gap-4">
      <div className="flex flex-col gap-4 rounded-2xl bg-tint p-4 text-sm">
        <p className="font-medium text-brand">
          {detail.searchHelp.visibility === 'DIFFICULT'
            ? 'YouTube 검색에서 영상을 찾기 어려울 수 있습니다'
            : 'YouTube에서 노래 찾아보기'}
        </p>
        <p className="break-words text-muted">
          권장 검색어: <span className="select-text">{recommended}</span>
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button disabled={copying} onClick={() => void copy()}>
          {copying ? '복사 중…' : '검색어 복사'}
        </Button>
        <Button asChild>
          <a
            href={`https://www.youtube.com/results?search_query=${encodeURIComponent(recommended)}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            YouTube 검색 ↗
          </a>
        </Button>
      </div>
      {notice && (
        <p role="status" className="text-sm text-muted">
          {notice}
        </p>
      )}
    </section>
  )
}
