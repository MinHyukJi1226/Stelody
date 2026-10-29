import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { makeAdminSong } from '@/test/admin-song'
import { createAdminSong, emptySongDraft } from './song-create'
import { toInput } from './song-draft'

afterEach(() => vi.restoreAllMocks())
const input = () =>
  toInput({ ...emptySongDraft(), title: '새 곡' }, 0, '수동 등록')
const response = () => ({
  item: {
    ...makeAdminSong(),
    title: '새 곡',
    type: 'COVER',
    visibility: 'DRAFT',
    representativeVideoId: null,
    videos: [],
  },
  possibleDuplicateIds: [],
})
describe('수동 초안 등록', () => {
  it('곡 생성만 요청하며 참여자를 추정하거나 영상·공개를 추가하지 않는다', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(response())
    const signal = new AbortController().signal
    await createAdminSong(input(), signal)
    expect(request).toHaveBeenCalledExactlyOnceWith('/admin/songs', {
      method: 'POST',
      body: input(),
      signal,
    })
    expect(input()).toMatchObject({
      version: 0,
      visibility: 'DRAFT',
      memberIds: [],
      externalArtistIds: [],
      searchVisibility: 'UNCHECKED',
    })
    expect(input()).not.toHaveProperty('representativeVideoId')
  })
  it.each([
    { visibility: 'PUBLISHED' as const },
    { version: 2 },
    { representativeVideoId: makeAdminSong().id },
  ])('초안 생성 범위를 벗어난 입력은 전송하지 않는다: %j', async (extra) => {
    const request = vi.spyOn(apiClient, 'request')
    await expect(
      createAdminSong({ ...input(), ...extra }, new AbortController().signal),
    ).rejects.toThrow()
    expect(request).not.toHaveBeenCalled()
  })
  it.each([
    { visibility: 'PUBLISHED' },
    { type: 'ORIGINAL' },
    { representativeVideoId: makeAdminSong().id },
    { videos: makeAdminSong().videos },
  ])(
    '초안 생성과 다른 성공 응답을 완료로 확정하지 않는다: %j',
    async (extra) => {
      vi.spyOn(apiClient, 'request').mockResolvedValue({
        ...response(),
        item: { ...response().item, ...extra },
      })
      await expect(
        createAdminSong(input(), new AbortController().signal),
      ).rejects.toBeInstanceOf(ApiResponseError)
    },
  )
  it('응답 유실을 자동 재등록하지 않는다', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockRejectedValue(new TypeError('network'))
    await expect(
      createAdminSong(input(), new AbortController().signal),
    ).rejects.toThrow('network')
    expect(request).toHaveBeenCalledTimes(1)
  })
})
