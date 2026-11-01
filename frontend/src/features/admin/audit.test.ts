import { expect, it } from 'vitest'
import { changes, auditValue } from './audit'
import { auditRecoverySchema } from './audit-recovery'
it('변경된 필드와 삭제·추가·null을 구분한다', () => {
  expect(
    changes(
      { title: 'a', removed: 1, same: true },
      { title: 'b', added: null, same: true },
    ),
  ).toEqual([
    { key: 'title', before: 'a', after: 'b' },
    { key: 'removed', before: 1, after: undefined },
    { key: 'added', before: undefined, after: null },
  ])
  expect(auditValue(undefined)).not.toBe(auditValue(null))
})
it('숫자·배열·문자열·불리언·null 전체 값을 보존한다', () => {
  for (const value of [
    0,
    false,
    '<script>alert(1)</script>',
    null,
    [1, 2],
    { a: { b: 3 } },
  ])
    expect(changes(null, value)[0]?.after).toEqual(value)
  expect(auditValue(0)).toBe('0')
  expect(auditValue(false)).toBe('false')
})
it('복귀와 복구 문맥을 검증한다', () => {
  expect(
    auditRecoverySchema.safeParse({
      target: { kind: 'invalid', id: null, name: '' },
      page: 0,
      entryId: null,
    }).success,
  ).toBe(false)
})
