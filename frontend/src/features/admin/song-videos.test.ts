import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import { ApiResponseError } from '@/shared/api/errors'
import { makeAdminSong, mid, sid } from '@/test/admin-song'
import {
  attachSongVideo,
  getVideoCandidate,
  type VideoCandidate,
} from './song-videos'

afterEach(() => vi.restoreAllMocks())
const video = () => ({
  ...makeAdminSong().videos[0]!,
  youtubeId: 'testVideo01',
})
const candidate = {
  id: mid,
  youtubeId: 'testVideo01',
  version: 3,
} as VideoCandidate
const input = {
  version: 3,
  songId: sid,
  songVersion: 5,
  kind: 'OFFICIAL_COVER' as const,
  reason: '영상 확인 후 연결',
}
it('선택 후보·곡의 버전과 연결 사유를 함께 보내고 연결 영상을 확인한다', async () => {
  const request = vi.spyOn(apiClient, 'request').mockResolvedValue(video())
  const signal = new AbortController().signal
  expect(await attachSongVideo(candidate, input, signal)).toEqual(video())
  expect(request).toHaveBeenCalledExactlyOnceWith(
    `/admin/reviews/${mid}/registration`,
    { method: 'POST', body: input, signal },
  )
})
it.each([
  { songId: mid },
  { youtubeId: 'otherVideo1' },
  { kind: 'OTHER' },
  { version: -1 },
])(
  '다른 곡·영상·종류의 응답을 연결 성공으로 처리하지 않는다: %j',
  async (extra) => {
    vi.spyOn(apiClient, 'request').mockResolvedValue({ ...video(), ...extra })
    await expect(
      attachSongVideo(candidate, input, new AbortController().signal),
    ).rejects.toBeInstanceOf(ApiResponseError)
  },
)
it('응답 유실 시 연결을 자동 재요청하지 않는다', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockRejectedValue(new TypeError('network'))
  await expect(
    attachSongVideo(candidate, input, new AbortController().signal),
  ).rejects.toThrow('network')
  expect(request).toHaveBeenCalledTimes(1)
})
it('최신 후보의 ID나 필수 정보가 다르면 확인 결과로 사용하지 않는다', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue({ id: sid, version: 3 })
  await expect(
    getVideoCandidate(mid, new AbortController().signal),
  ).rejects.toBeInstanceOf(ApiResponseError)
})
