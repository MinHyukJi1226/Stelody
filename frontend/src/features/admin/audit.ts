import { z } from 'zod'
import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { adminKeys } from './queries'
export const auditKinds = {
  songs: '곡',
  works: '원곡',
  artists: '아티스트',
  members: '멤버',
  channels: '공식 채널',
  videos: '영상',
  reviews: '개별 검토 후보',
  'collection-rules': '수집 분류 규칙',
} as const
export type AuditKind = keyof typeof auditKinds
const targetTypes: Record<AuditKind, string> = {
  songs: 'SONGS',
  works: 'WORKS',
  artists: 'ARTISTS',
  members: 'MEMBERS',
  channels: 'CHANNELS',
  videos: 'VIDEOS',
  reviews: 'REVIEWS',
  'collection-rules': 'COLLECTION_RULES',
}
export const auditSchema = z.object({
  id: z.uuid(),
  targetType: z.string(),
  targetId: z.uuid(),
  actorId: z.uuid().nullable(),
  action: z.string(),
  before: z.json(),
  after: z.json(),
  reason: z.string(),
  changedAt: z.iso.datetime({ offset: true }),
})
export type Audit = z.infer<typeof auditSchema>
const pageSchema = z.object({
  items: z.array(auditSchema),
  page: z.number().int().nonnegative(),
  size: z.number().int().min(1).max(50),
  hasNext: z.boolean(),
})
export const auditOptions = (
  user: string,
  kind: AuditKind,
  id: string | null,
  page: number,
  size: number,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), 'audit', kind, id, page, size],
    queryFn: async ({ signal }) => {
      const path =
        kind === 'collection-rules'
          ? '/admin/collection-rules/audit'
          : `/admin/${kind}/${encodeURIComponent(id ?? '')}/audit`
      const data = await apiClient.request<unknown>(
        `${path}?${new URLSearchParams({ page: String(page), size: String(size) })}`,
        { signal, cache: 'no-store' },
      )
      signal.throwIfAborted()
      const p = pageSchema.safeParse(data)
      if (
        !p.success ||
        p.data.page !== page ||
        p.data.size !== size ||
        new Set(p.data.items.map((x) => x.id)).size !== p.data.items.length ||
        p.data.items.some(
          (x) =>
            x.targetType !== targetTypes[kind] ||
            (kind !== 'collection-rules' && x.targetId !== id),
        )
      )
        throw new ApiResponseError()
      return p.data
    },
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    gcTime: 0,
  })
const labels: Record<string, string> = {
  title: '곡명·제목',
  aliases: '별칭',
  memberIds: '참여 멤버',
  artistIds: '아티스트',
  participants: '참여자',
  name: '이름',
  visibility: '공개 설정',
  type: '곡 유형',
  configuration: '분류 규칙',
  reason: '사유',
  links: '링크',
  karaoke: '노래방',
  representativeVideoId: '대표 영상',
  workId: '원곡',
  status: '상태',
  reviewNote: '검토 사유',
}
export const auditFieldLabel = (key: string) => labels[key] ?? key
export function changes(before: Audit['before'], after: Audit['after']) {
  if (
    before &&
    after &&
    typeof before === 'object' &&
    typeof after === 'object' &&
    !Array.isArray(before) &&
    !Array.isArray(after)
  ) {
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
    return keys
      .filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]))
      .map((k) => ({ key: k, before: before[k], after: after[k] }))
  }
  return [{ key: '전체 값', before, after }]
}
export function auditValue(value: Audit['before'] | undefined) {
  if (value === undefined) return '항목 없음'
  if (value === null) return '정보 없음 (null)'
  return JSON.stringify(value, null, 2)
}
