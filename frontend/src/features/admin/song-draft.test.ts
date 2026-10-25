import { describe, it, expect } from 'vitest'
import {
  chosenDraft,
  fields,
  normalizedDraft,
  reconcile,
  toDraft,
  toInput,
  validateDraft,
} from './song-draft'
import { makeAdminSong } from '@/test/admin-song'

describe('관리자 곡 수정 데이터 보호', () => {
  it('한 탭만 수정해도 관계·링크·노래방 정보를 보존하며 읽기 전용 값은 전송하지 않는다', () => {
    const song = makeAdminSong(),
      draft = toDraft(song)
    draft.title = ' 수정 제목 '
    const input = toInput(draft, song.version, ' 변경 사유 ')
    expect(input.memberIds).toEqual(song.memberIds)
    expect(input.externalArtistIds).toEqual(song.externalArtistIds)
    expect(input.links).toEqual(
      song.links.map(({ platform, url, sourceUrl }) => ({
        platform,
        url,
        sourceUrl,
      })),
    )
    expect(input.karaoke[0]).toEqual({
      provider: 'TJ',
      status: 'REGISTERED',
      number: '12345',
      sourceUrl: 'https://tjmedia.com/',
    })
    expect(input).not.toHaveProperty('videos')
    expect(input).not.toHaveProperty('searchCheckedAt')
    expect(input).not.toHaveProperty('id')
  })
  it('명시적으로 비운 배열과 null 연결, 조건 해제를 전체 저장에 반영한다', () => {
    const d = toDraft(makeAdminSong())
    Object.assign(d, {
      aliases: [],
      memberIds: [],
      workId: null,
      representativeVideoId: null,
      isSpecialEvent: false,
      specialEventLabel: '이전 문구',
      searchVisibility: 'UNCHECKED',
      recommendedSearchQuery: '이전 검색어',
    })
    d.karaoke[0]!.status = 'NOT_LISTED'
    const input = toInput(d, 5, '확인')
    expect(JSON.parse(JSON.stringify(input))).toMatchObject({
      aliases: [],
      memberIds: [],
      specialEventLabel: null,
    })
    expect(input).not.toHaveProperty('workId')
    expect(input).not.toHaveProperty('representativeVideoId')
    expect(input).not.toHaveProperty('recommendedSearchQuery')
    expect(input.karaoke[0]).not.toHaveProperty('number')
    expect(normalizedDraft(d)).toMatchObject({
      specialEventLabel: '',
      recommendedSearchQuery: '',
    })
  })
  it('양쪽 변경은 선택을 강제하며 최신 값만 바뀐 항목은 누락하지 않는다', () => {
    const b = toDraft(makeAdminSong()),
      d = structuredClone(b),
      s = structuredClone(b)
    d.title = '내 제목'
    s.title = '최신 제목'
    s.aliases = ['최신 별칭']
    d.visibility = 'DRAFT'
    const choices = reconcile(b, d, s)
    expect(choices.title).toBeUndefined()
    expect(choices.aliases).toBe('latest')
    expect(choices.visibility).toBe('draft')
    choices.title = 'draft'
    expect(fields.every(([key]) => choices[key])).toBe(true)
    expect(chosenDraft(d, s, choices)).toMatchObject({
      title: '내 제목',
      aliases: ['최신 별칭'],
      visibility: 'DRAFT',
    })
    expect(b.title).toBe('노래 제목')
  })
  it('참여자·링크 배열 충돌을 임의로 합치지 않는다', () => {
    const b = toDraft(makeAdminSong()),
      d = structuredClone(b),
      s = structuredClone(b)
    d.memberIds = []
    s.memberIds = ['다른 참여자']
    d.links = []
    s.links[0]!.platform = '최신 플랫폼'
    const choices = reconcile(b, d, s)
    expect(choices.memberIds).toBeUndefined()
    expect(choices.links).toBeUndefined()
  })
  it('특별 목적·검색 어려움·등록/미등록 확인은 각 필수 입력을 요구한다', () => {
    const d = toDraft(makeAdminSong())
    d.isSpecialEvent = true
    d.searchVisibility = 'DIFFICULT'
    d.recommendedSearchQuery = ''
    d.karaoke[0]!.number = ''
    d.karaoke[1]!.status = 'NOT_LISTED'
    expect(validateDraft(d, '')).toMatchObject({
      reason: expect.any(String),
      specialEventLabel: expect.any(String),
      recommendedSearchQuery: expect.any(String),
      'karaoke[0].number': expect.any(String),
      'karaoke[1].sourceUrl': expect.any(String),
    })
  })
})
