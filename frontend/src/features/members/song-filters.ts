import { z } from 'zod'

const filtersSchema = z
  .object({
    type: z.enum(['ALL', 'ORIGINAL', 'COVER']),
    multipleParticipants: z.enum(['true', 'false']).optional(),
    participation: z.enum(['ALL', 'SOLO', 'MULTIPLE']).optional(),
    sort: z.enum(['LATEST', 'VIEWS']),
    collaboration: z.enum(['true', 'false']).optional(),
  })
  .superRefine((value, context) => {
    const legacy = [value.multipleParticipants, value.collaboration].filter(
      (v) => v !== undefined,
    )
    const participation =
      value.participation ?? (legacy[0] === 'true' ? 'MULTIPLE' : 'ALL')
    if (
      legacy.some((v) => (v === 'true' ? 'MULTIPLE' : 'ALL') !== participation)
    ) {
      context.addIssue({
        code: 'custom',
        path: ['multipleParticipants'],
        message: '참여 인원 필터 값이 서로 다릅니다.',
      })
    }
  })
  .transform((value) => ({
    type: value.type,
    multipleParticipants: value.participation
      ? value.participation === 'MULTIPLE'
      : (value.multipleParticipants ?? value.collaboration) === 'true',
    ...(value.participation ? { participation: value.participation } : {}),
    sort: value.sort,
  }))

export type MemberSongFilters = z.infer<typeof filtersSchema>
export const defaultMemberSongFilters: MemberSongFilters = {
  type: 'ALL',
  multipleParticipants: false,
  sort: 'LATEST',
}

export const readMemberSongFilters = (params: URLSearchParams) =>
  filtersSchema.safeParse({
    type: params.get('type') ?? 'ALL',
    multipleParticipants: params.get('multipleParticipants') ?? undefined,
    participation: params.get('participation') ?? undefined,
    collaboration: params.get('collaboration') ?? undefined,
    sort: params.get('sort') ?? 'LATEST',
  })

export function memberSongParams(filters: MemberSongFilters) {
  const params = new URLSearchParams()
  if (filters.type !== 'ALL') params.set('type', filters.type)
  if (filters.participation) {
    if (filters.participation !== 'ALL')
      params.set('participation', filters.participation)
  } else if (filters.multipleParticipants)
    params.set('multipleParticipants', 'true')
  if (filters.sort !== 'LATEST') params.set('sort', filters.sort)
  return params
}
