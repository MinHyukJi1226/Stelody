import { z } from 'zod'

const isoDateSchema = z.iso.date()
const dateSchema = z
  .string()
  .refine(
    (date) => !date || isoDateSchema.safeParse(date).success,
    'YYYY-MM-DD 형식의 올바른 날짜를 입력해 주세요.',
  )
  .refine(
    (date) => !date || (date >= '1900-01-01' && date <= '2100-12-31'),
    '1900년부터 2100년 사이의 날짜를 입력해 주세요.',
  )
  .optional()

const criteriaSchema = z
  .object({
    q: z.string().trim().max(200, '검색어는 200자 이하로 입력해 주세요.'),
    type: z.enum(['ALL', 'ORIGINAL', 'COVER']),
    memberIds: z.array(z.string().uuid()).max(20),
    sort: z.enum(['RELEVANCE', 'LATEST', 'VIEWS']),
    startDate: dateSchema,
    endDate: dateSchema,
  })
  .superRefine((value, context) => {
    if (value.startDate && value.endDate && value.startDate > value.endDate) {
      for (const field of ['startDate', 'endDate']) {
        context.addIssue({
          code: 'custom',
          path: [field],
          message: '시작일은 종료일보다 늦을 수 없습니다.',
        })
      }
    }
  })
export type SearchCriteria = z.infer<typeof criteriaSchema>
export const validateSearchCriteria = (criteria: SearchCriteria) =>
  criteriaSchema.safeParse(criteria)

export const hasSearchConditions = (criteria: SearchCriteria) =>
  Boolean(
    criteria.q.trim() ||
    criteria.type !== 'ALL' ||
    criteria.memberIds.length ||
    criteria.startDate ||
    criteria.endDate,
  )

export function readSearchParams(params: URLSearchParams) {
  if (params.has('year')) {
    return {
      criteria: null,
      error:
        params.has('startDate') || params.has('endDate')
          ? '연도와 날짜 범위를 함께 지정할 수 없습니다.'
          : '연도 조건 대신 시작일·종료일을 사용해 주세요.',
      active: true,
    }
  }
  const q = params.get('q') ?? ''
  const hasConditions = [
    'q',
    'type',
    'memberIds',
    'sort',
    'startDate',
    'endDate',
  ].some((key) => params.has(key))
  const result = criteriaSchema.safeParse({
    q,
    type: params.get('type') ?? 'ALL',
    memberIds: [...new Set(params.getAll('memberIds'))].sort(),
    sort: params.get('sort') ?? (q.trim() ? 'RELEVANCE' : 'LATEST'),
    startDate: params.get('startDate') ?? undefined,
    endDate: params.get('endDate') ?? undefined,
  })
  const issue = result.success ? undefined : result.error.issues[0]
  return {
    criteria: result.success ? result.data : null,
    error: result.success
      ? hasConditions && !hasSearchConditions(result.data)
        ? '검색어를 입력하거나 필터를 선택해 주세요.'
        : null
      : ['q', 'startDate', 'endDate'].includes(String(issue?.path[0]))
        ? issue!.message
        : '검색 조건이 올바르지 않습니다. 초기화 후 다시 검색해 주세요.',
    active: result.success && hasSearchConditions(result.data),
  }
}

export function searchParams(criteria: SearchCriteria) {
  const params = new URLSearchParams({ q: criteria.q.trim() })
  if (criteria.type !== 'ALL') params.set('type', criteria.type)
  for (const id of [...new Set(criteria.memberIds)].sort())
    params.append('memberIds', id)
  if (criteria.sort !== (criteria.q.trim() ? 'RELEVANCE' : 'LATEST'))
    params.set('sort', criteria.sort)
  if (criteria.startDate) params.set('startDate', criteria.startDate)
  if (criteria.endDate) params.set('endDate', criteria.endDate)
  return params
}
