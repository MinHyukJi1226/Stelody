import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { z } from 'zod'
import { memberDetailOptions } from '@/features/members/queries'
import { ApiError } from '@/shared/api/errors'
import { StatePanel } from '@/shared/ui/state-panel'
import { MemberProfile } from './components/member-profile'
import { MemberSkeleton } from './components/member-skeleton'
import { MemberState } from './components/member-state'
import { MemberSongs } from './components/member-songs'

export function MemberDetailPage() {
  const { memberId = '' } = useParams()
  const valid = z.uuid().safeParse(memberId).success
  const query = useQuery({ ...memberDetailOptions(memberId), enabled: valid })
  const unavailable =
    !valid ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <div className="flex min-w-0 flex-1 flex-col gap-8 px-5 py-8 md:px-8">
        {unavailable ? (
          <>
            <h1 className="sr-only">멤버를 찾을 수 없습니다</h1>
            <div className="flex flex-1 items-center">
              <MemberState
                kind="unavailable"
                title="이 화면에 접근할 수 없습니다"
                description="존재하지 않거나 공개되지 않은 멤버입니다."
              />
            </div>
          </>
        ) : query.isPending ? (
          <MemberSkeleton />
        ) : !query.data ? (
          <>
            <h1 className="sr-only">멤버 상세 오류</h1>
            <div className="flex flex-1 items-center">
              <MemberState
                kind="error"
                title="불러오지 못했어요"
                description="연결 상태를 확인한 뒤 다시 시도해 주세요."
                onRetry={() => void query.refetch()}
              />
            </div>
          </>
        ) : (
          <>
            {query.isError && (
              <StatePanel
                title="멤버 정보를 갱신하지 못했습니다"
                description="이전에 불러온 정보를 표시하고 있습니다."
                onRetry={() => void query.refetch()}
              />
            )}
            <MemberProfile detail={query.data} />
            <MemberSongs key={memberId} memberId={memberId} />
          </>
        )}
      </div>
      <footer className="grid gap-1 px-5 pt-5 pb-6 text-xs text-muted md:px-10">
        <p>Stelody · 팬이 만드는 노래 아카이브</p>
        <p>공식 서비스가 아닙니다.</p>
      </footer>
    </div>
  )
}
