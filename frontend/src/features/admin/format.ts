import { ApiError } from '@/shared/api/errors'

export const isAdminAccessError = (error: unknown) =>
  error instanceof ApiError && (error.status === 401 || error.status === 403)

export function formatAdminTime(value: string) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(value))
  const part = (type: string) => parts.find((p) => p.type === type)?.value
  return `${part('year')}.${part('month')}.${part('day')} ${part('hour')}:${part('minute')} KST`
}
export const adminLabel = (value: string) =>
  (
    ({
      VIDEO: '조회수·영상 상태 갱신',
      DISCOVERY: '신규 영상 탐색',
      SUCCEEDED: '성공',
      FAILED: '실패',
      QUOTA_EXHAUSTED: '할당량 초과',
      TIMED_OUT: '시간 초과',
      RUNNING: '실행 중',
      PENDING: '대기 중',
      QUEUED: '대기 중',
      PUBLIC: '공개',
      PUBLISHED: '공개 설정',
      HIDDEN: '숨김',
      DRAFT: '초안',
      AVAILABLE: '공개',
      UNLISTED: '일부 공개',
      PRIVATE: '비공개',
      DELETED: '삭제',
      UNKNOWN: '확인 필요',
      work: '원곡',
      originalArtists: '원곡 아티스트',
      aliases: '별칭 (선택)',
      searchCheck: '검색 확인',
      TJ: 'TJ 확인',
      KY: 'KY 확인',
      BIRTHDAY_DATE_MATCH: '생일 날짜 일치',
      DEBUT_DATE_MATCH: '데뷔 날짜 일치',
    }) as Record<string, string>
  )[value] ?? value

export const songLabel = (value: string) =>
  (
    ({
      ORIGINAL: '오리지널',
      COVER: '커버',
      UNCHECKED: '미확인',
      NORMAL: '정상 검색',
      DIFFICULT: '검색 어려움',
      UNKNOWN: '미확인',
      NOT_LISTED: '미등록 확인',
      REGISTERED: '등록 확인',
      OFFICIAL_COVER: '공식 커버',
      OFFICIAL_MV: '공식 MV',
      AUDIO: '음원',
      REUPLOAD: '재업로드',
      OTHER: '기타',
      UNAVAILABLE: '이용 불가',
    }) as Record<string, string>
  )[value] ?? adminLabel(value)

export function listPage(
  value: string | null,
  fallback = 0,
  max = Number.MAX_SAFE_INTEGER,
) {
  return value !== null &&
    /^\d+$/.test(value) &&
    Number.isSafeInteger(+value) &&
    +value <= max
    ? +value
    : fallback
}
