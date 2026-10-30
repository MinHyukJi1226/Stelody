import { describe, expect, it } from 'vitest'
import {
  emptyMemberDraft,
  memberDraft,
  memberInput,
  validateMember,
  memberSchema,
  memberReturnPath,
  memberEditPath,
  type AdminMember,
} from './members'

const id = '00000000-0000-4000-8000-000000000001'
const member: AdminMember = {
  id,
  version: 9,
  name: '멤버',
  unitName: '유닛',
  generation: 2,
  activityStatus: 'GRADUATED',
  profileImageUrl: 'https://example.com/profile.png',
  chzzkUrl: 'https://chzzk.naver.com/member',
  xUrl: 'https://x.com/member',
  birthdayMonth: 2,
  birthdayDay: 29,
  debutDate: '2024-02-29',
  aliases: ['별칭'],
}
describe('관리자 멤버 입력', () => {
  it('이름만 수정해도 기존의 선택 정보를 모두 보존한다', () => {
    const d = memberDraft(member)
    d.name = ' 새 이름 '
    expect(memberInput(d, member.version, ' 공식 정보 확인 ')).toEqual({
      version: 9,
      name: '새 이름',
      unitName: '유닛',
      generation: 2,
      activityStatus: 'GRADUATED',
      profileImageUrl: member.profileImageUrl,
      chzzkUrl: member.chzzkUrl,
      xUrl: member.xUrl,
      birthdayMonth: 2,
      birthdayDay: 29,
      debutDate: '2024-02-29',
      aliases: ['별칭'],
      reason: '공식 정보 확인',
    })
    expect(validateMember(d, '확인')).toEqual({})
  })
  it('모든 선택 정보를 비우면 기존 값을 지우는 DTO 계약으로 전송한다', () => {
    expect(
      memberInput({ ...emptyMemberDraft(), name: '멤버' }, 9, '삭제'),
    ).toEqual({
      version: 9,
      name: '멤버',
      activityStatus: 'ACTIVE',
      aliases: [],
      unitName: null,
      chzzkUrl: null,
      xUrl: null,
      reason: '삭제',
    })
  })
  it('미등록 날짜를 임의로 채우지 않는다', () => {
    const d = memberDraft({
      ...member,
      birthdayMonth: null,
      birthdayDay: null,
      debutDate: null,
    })
    expect(d.birthday).toBe('')
    expect(d.debutDate).toBe('')
    expect(memberInput(d, 9, '확인')).not.toHaveProperty('birthdayMonth')
  })
  it.each(['2 / 30', '4 / 31', '13 / 1', '0 / 1', '2 /', '/ 3', '2-3'])(
    '존재하지 않거나 일부만 입력된 생일 %s를 막는다',
    (birthday) =>
      expect(
        validateMember({ ...memberDraft(member), birthday }, '확인'),
      ).toHaveProperty('birthday'),
  )
  it('윤년 생일은 허용하지만 잘못된 데뷔일은 거절한다', () => {
    expect(validateMember(memberDraft(member), '확인')).toEqual({})
    expect(
      validateMember(
        { ...memberDraft(member), debutDate: '2023-02-29' },
        '확인',
      ),
    ).toHaveProperty('debutDate')
    expect(memberSchema.safeParse({ ...member, birthdayDay: 30 }).success).toBe(
      false,
    )
    expect(
      memberSchema.safeParse({ ...member, birthdayDay: null }).success,
    ).toBe(false)
  })
  it.each(['0', '-1', '32768', '1.5', 'NaN'])(
    '기수 %s를 거절한다',
    (generation) =>
      expect(
        validateMember({ ...memberDraft(member), generation }, '확인'),
      ).toHaveProperty('generation'),
  )
  it('필수 입력과 별칭 개수·길이 및 공식 URL을 검증한다', () => {
    const d = {
      ...emptyMemberDraft(),
      aliases: Array<string>(51).fill('별칭'),
      profileImageUrl: 'http://example.com/a',
      chzzkUrl: 'https://localhost/a',
      xUrl: 'https://user@example.com',
    }
    expect(validateMember(d, '')).toMatchObject({
      name: expect.any(String),
      reason: expect.any(String),
      aliases: expect.any(String),
      profileImageUrl: expect.any(String),
      chzzkUrl: expect.any(String),
      xUrl: expect.any(String),
    })
    expect(
      validateMember(
        { ...memberDraft(member), aliases: ['a'.repeat(201)] },
        '확인',
      ),
    ).toHaveProperty('aliases[0]')
  })
})
describe('멤버 목록 복귀', () => {
  it('검색어·페이지를 유지하고 다른 경로로의 복귀는 허용하지 않는다', () => {
    const path = '/admin/members?q=%EB%A9%A4%EB%B2%84&page=2&size=10'
    expect(memberReturnPath(path)).toBe(path)
    expect(
      new URL(memberEditPath(id, path), 'https://example.com').searchParams.get(
        'returnTo',
      ),
    ).toBe(path)
    for (const value of [
      '//evil.example/admin/members',
      'https://evil.example/admin/members',
      '/admin/members/new',
      '/admin/members/../songs',
    ])
      expect(memberReturnPath(value)).toBe('/admin/members')
  })
})
