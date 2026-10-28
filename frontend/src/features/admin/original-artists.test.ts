import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import {
  getArtist,
  getWork,
  getArtistRegistration,
  registerArtist,
  validateArtist,
  workArtistInput,
  type Work,
} from './original-artists'
import { makeAdminSong, aid, wid } from '@/test/admin-song'
afterEach(() => vi.restoreAllMocks())
describe('원곡 아티스트 저장 입력', () => {
  it('조회용 속성만 빼고 제목·별칭·링크·노래방과 null 의미를 보존한다', () => {
    const song = makeAdminSong()
    const work: Work = {
      id: wid,
      version: 7,
      title: '공유 원곡',
      aliases: ['원제'],
      artistIds: [aid],
      links: song.links,
      karaoke: [
        ...song.karaoke,
        {
          id: wid,
          provider: 'KY',
          status: 'UNKNOWN',
          number: null,
          sourceUrl: null,
          checkedAt: null,
        },
      ],
    }
    const before = structuredClone(work)
    expect(workArtistInput(work, [], '  크레딧 정정  ')).toEqual({
      version: 7,
      title: '공유 원곡',
      aliases: ['원제'],
      artistIds: [],
      links: song.links.map(({ platform, url, sourceUrl }) => ({
        platform,
        url,
        sourceUrl,
      })),
      karaoke: [
        {
          provider: 'TJ',
          status: 'REGISTERED',
          number: '12345',
          sourceUrl: 'https://tjmedia.com/',
        },
        { provider: 'KY', status: 'UNKNOWN' },
      ],
      reason: '크레딧 정정',
    })
    expect(work).toEqual(before)
  })
  it('이름·사유·별칭 제한을 지키며 별칭 없는 등록을 허용한다', () => {
    expect(validateArtist('아티스트', [], '크레딧 확인')).toEqual({})
    expect(
      validateArtist(' ', [' ', '가'.repeat(201)], '가'.repeat(501)),
    ).toMatchObject({
      name: expect.any(String),
      'aliases[0]': expect.any(String),
      'aliases[1]': expect.any(String),
      reason: expect.any(String),
    })
  })
  it('이름만 있는 불완전 아티스트 응답을 편집 가능한 값으로 쓰지 않는다', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValue({ id: aid, name: '이름' })
    await expect(getArtist(aid, new AbortController().signal)).rejects.toThrow()
  })
  it('불완전 원곡 응답에서 빠진 공유 정보를 빈 값으로 저장하지 않는다', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValue({
      id: wid,
      title: '원곡',
      artistIds: [],
    })
    await expect(getWork(wid, new AbortController().signal)).rejects.toThrow()
  })
})
describe('아티스트 등록 결과 API', () => {
  const empty = {
    requestId: wid,
    status: 'NOT_PROCESSED',
    artistId: null,
    errorCode: null,
    createdAt: null,
    expiresAt: null,
    finishedAt: null,
    pollAfterSeconds: null,
  }
  it('등록은 보관한 UUID 헤더와 본문 전체를 그대로 전송한다', async () => {
    const body = {
      version: 0,
      name: '아티스트',
      aliases: ['B', 'A'],
      reason: '확인',
    }
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValue({
      item: { id: aid, version: 0, name: body.name, aliases: body.aliases },
      possibleDuplicateIds: [],
    })
    const signal = new AbortController().signal
    await registerArtist(wid, body, signal)
    expect(spy).toHaveBeenCalledWith('/admin/artists', {
      method: 'POST',
      body,
      signal,
      headers: { 'Idempotency-Key': wid },
    })
  })
  it('미접수와 결과 조회 실패를 구분한다', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValue(empty)
    await expect(
      getArtistRegistration(wid, new AbortController().signal),
    ).resolves.toEqual(empty)
    spy.mockRejectedValue(new Error('unavailable'))
    await expect(
      getArtistRegistration(wid, new AbortController().signal),
    ).rejects.toThrow('unavailable')
  })
  it('다른 UUID·불완전 완료·모순된 상태를 완료나 미접수로 표시하지 않는다', async () => {
    const spy = vi.spyOn(apiClient, 'request')
    for (const value of [
      { ...empty, requestId: aid },
      { ...empty, status: 'COMPLETED' },
      { ...empty, artistId: aid },
      { ...empty, status: 'PROCESSING', pollAfterSeconds: 0 },
    ]) {
      spy.mockResolvedValue(value)
      await expect(
        getArtistRegistration(wid, new AbortController().signal),
      ).rejects.toThrow()
    }
  })
})
