import { z } from 'zod'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { songSchema, type SongInput } from './songs'
import type { Draft } from './song-draft'

export const emptySongDraft = (): Draft => ({
  title: '',
  type: 'COVER',
  workId: null,
  visibility: 'DRAFT',
  representativeVideoId: null,
  aliases: [],
  memberIds: [],
  externalArtistIds: [],
  isSpecialEvent: false,
  specialEventLabel: '',
  searchVisibility: 'UNCHECKED',
  recommendedSearchQuery: '',
  links: [],
  karaoke: (['TJ', 'KY'] as const).map((provider) => ({
    provider,
    status: 'UNKNOWN',
    number: '',
    sourceUrl: '',
  })),
})
export async function createAdminSong(body: SongInput, signal: AbortSignal) {
  if (
    body.version !== 0 ||
    body.visibility !== 'DRAFT' ||
    body.representativeVideoId
  )
    throw new Error('먼저 영상 없는 곡 초안을 등록해주세요.')
  const data = await apiClient.request<unknown>('/admin/songs', {
    method: 'POST',
    body,
    signal,
  })
  const parsed = z
    .object({ item: songSchema, possibleDuplicateIds: z.array(z.uuid()) })
    .safeParse(data)
  if (
    !parsed.success ||
    parsed.data.item.visibility !== 'DRAFT' ||
    parsed.data.item.representativeVideoId !== null ||
    parsed.data.item.videos.length !== 0 ||
    parsed.data.item.type !== body.type
  )
    throw new ApiResponseError()
  signal.throwIfAborted()
  return parsed.data
}
