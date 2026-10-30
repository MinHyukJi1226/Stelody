import { describe, it, expect } from 'vitest'
import {
  emptyChannelDraft,
  channelDraft,
  channelInput,
  validateChannel,
  channelReturnPath,
} from './channels'
const id = '00000000-0000-4000-8000-000000000001'
describe('공식 채널 입력', () => {
  it('수정에서 식별자·유형·수집·연결 멤버를 보존한다', () => {
    const c = {
      id,
      version: 4,
      youtubeId: 'UC' + 'a'.repeat(22),
      name: '공식',
      memberId: id,
      channelType: 'MEMBER' as const,
      collectionEnabled: true,
    }
    const { id: ignored, ...input } = c
    void ignored
    expect(channelInput(channelDraft(c), 4, ' 확인 ')).toEqual({
      ...input,
      reason: '확인',
    })
  })
  it('멤버 연결을 비우면 생략 계약으로 삭제한다', () =>
    expect(
      channelInput(
        {
          ...emptyChannelDraft(),
          youtubeId: 'UC' + 'a'.repeat(22),
          name: '그룹',
        },
        0,
        '확인',
      ),
    ).not.toHaveProperty('memberId'))
  it('빈 이름·ID·사유는 전송하지 않는다', () =>
    expect(Object.keys(validateChannel(emptyChannelDraft(), ''))).toEqual([
      'youtubeId',
      'name',
      'reason',
    ]))
  it('외부 채널 수집과 그룹 멤버 연결을 거절한다', () => {
    const d = {
      ...emptyChannelDraft(),
      youtubeId: 'UC' + 'a'.repeat(22),
      name: '채널',
      channelType: 'EXTERNAL' as const,
      collectionEnabled: true,
      memberId: id,
    }
    expect(validateChannel(d, '확인')).toHaveProperty('collectionEnabled')
    expect(validateChannel(d, '확인')).toHaveProperty('memberId')
  })
  it.each([
    'https://youtube.com/channel/UCabc',
    'UCabc',
    'UU' + 'a'.repeat(22),
  ])('잘못된 채널 ID %s 검증', (youtubeId) =>
    expect(
      validateChannel({ ...emptyChannelDraft(), youtubeId }, '확인'),
    ).toHaveProperty('youtubeId'),
  )
  it('검색·페이지 복귀 경로만 허용한다', () => {
    const p = '/admin/channels?q=UCtest&page=2&size=10'
    expect(channelReturnPath(p)).toBe(p)
    expect(channelReturnPath('//evil.example/admin/channels')).toBe(
      '/admin/channels',
    )
  })
})
