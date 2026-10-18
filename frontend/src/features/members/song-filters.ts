import { z } from 'zod'

const filtersSchema = z.object({
  type: z.enum(['ALL', 'ORIGINAL', 'COVER']),
  collaboration: z.enum(['true', 'false']).transform((v) => v === 'true'),
  sort: z.enum(['LATEST', 'VIEWS']),
})

export type MemberSongFilters = z.infer<typeof filtersSchema>
export const defaultMemberSongFilters: MemberSongFilters = {
  type: 'ALL',
  collaboration: false,
  sort: 'LATEST',
}

export const readMemberSongFilters = (params: URLSearchParams) =>
  filtersSchema.safeParse({
    type: params.get('type') ?? 'ALL',
    collaboration: params.get('collaboration') ?? 'false',
    sort: params.get('sort') ?? 'LATEST',
  })

export function memberSongParams(filters: MemberSongFilters) {
  const params = new URLSearchParams()
  if (filters.type !== 'ALL') params.set('type', filters.type)
  if (filters.collaboration) params.set('collaboration', 'true')
  if (filters.sort !== 'LATEST') params.set('sort', filters.sort)
  return params
}
