import { describe, expect, it } from 'vitest'
import {
  emptyWorkDraft,
  toWorkDraft,
  validateWork,
  workInput,
  workReturnPath,
  workEditPath,
} from './works'
import type { Work } from './original-artists'

const id = '00000000-0000-4000-8000-000000000001'
const work: Work = {
  id,
  version: 9,
  title: '原題',
  aliases: ['한글 제목'],
  artistIds: [id],
  links: [
    {
      id,
      platform: 'YouTube',
      url: 'https://youtube.com/example',
      sourceUrl: 'https://example.com/credit',
      checkedAt: '2026-10-29T00:00:00Z',
    },
  ],
  karaoke: [
    {
      id,
      provider: 'TJ',
      status: 'REGISTERED',
      number: '00123',
      sourceUrl: 'https://example.com/tj',
      checkedAt: '2026-10-28T00:00:00Z',
    },
    {
      id,
      provider: 'KY',
      status: 'UNKNOWN',
      number: null,
      sourceUrl: null,
      checkedAt: null,
    },
  ],
}
describe('공유 원곡 입력', () => {
  it('원래 없던 미확인 노래방 행을 제목 수정만으로 새로 만들지 않는다', () => {
    const original = { ...work, karaoke: [] }
    expect(
      workInput(toWorkDraft(original), 9, '제목 정리', original).karaoke,
    ).toEqual([])
    const draft = toWorkDraft(original)
    draft.karaoke[0] = {
      ...draft.karaoke[0]!,
      status: 'NOT_LISTED',
      sourceUrl: 'https://example.com/tj',
    }
    expect(workInput(draft, 9, '확인', original).karaoke).toEqual([
      {
        provider: 'TJ',
        status: 'NOT_LISTED',
        sourceUrl: 'https://example.com/tj',
      },
    ])
  })
  it('전체 정보를 유지하고 읽기 전용 ID·확인 시각을 요청에서 제외한다', () => {
    const input = workInput(toWorkDraft(work), 9, ' 제목 확인 ')
    expect(input).toEqual({
      version: 9,
      title: work.title,
      aliases: work.aliases,
      artistIds: [id],
      reason: '제목 확인',
      links: [
        {
          platform: 'YouTube',
          url: 'https://youtube.com/example',
          sourceUrl: 'https://example.com/credit',
        },
      ],
      karaoke: [
        {
          provider: 'TJ',
          status: 'REGISTERED',
          number: '00123',
          sourceUrl: 'https://example.com/tj',
        },
        { provider: 'KY', status: 'UNKNOWN' },
      ],
    })
    expect(work.links[0]!.checkedAt).toBe('2026-10-29T00:00:00Z')
  })
  it('미등록 확인에는 번호를 전송하지 않으며 근거가 필요하다', () => {
    const draft = toWorkDraft(work)
    draft.karaoke[0] = {
      ...draft.karaoke[0]!,
      status: 'NOT_LISTED',
      sourceUrl: '',
    }
    expect(validateWork(draft, '확인')).toHaveProperty('karaoke[0].sourceUrl')
    expect(workInput(draft, 9, '확인').karaoke[0]).not.toHaveProperty('number')
  })
  it('링크와 근거의 HTTPS 검증 및 빈 제목·사유를 확인한다', () => {
    const draft = emptyWorkDraft()
    draft.links.push({
      platform: '',
      url: 'javascript:alert(1)',
      sourceUrl: 'http://example.com',
    })
    expect(Object.keys(validateWork(draft, ''))).toEqual(
      expect.arrayContaining([
        'title',
        'reason',
        'links[0].platform',
        'links[0].url',
        'links[0].sourceUrl',
      ]),
    )
  })
  it('기존 아티스트·별칭·링크 제거를 빈 배열로 명시한다', () => {
    const draft = {
      ...toWorkDraft(work),
      artistIds: [],
      aliases: [],
      links: [],
    }
    expect(workInput(draft, 9, '정리')).toMatchObject({
      artistIds: [],
      aliases: [],
      links: [],
    })
  })
})
describe('원곡 목록 복귀', () => {
  it('검색어·페이지·크기는 유지하고 외부 주소·다른 경로는 거절한다', () => {
    const path = '/admin/works?q=title&page=2&size=10'
    expect(workReturnPath(path)).toBe(path)
    expect(
      new URL(workEditPath(id, path), 'https://example.com').searchParams.get(
        'returnTo',
      ),
    ).toBe(path)
    for (const value of [
      '//evil.example/admin/works',
      'https://evil.example/admin/works',
      '/admin/works/new',
      '/admin/songs',
      '/admin/works/../songs',
    ])
      expect(workReturnPath(value)).toBe('/admin/works')
  })
})
