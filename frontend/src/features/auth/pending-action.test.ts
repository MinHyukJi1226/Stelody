import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  consumePendingAction,
  preparePendingAction,
  readPendingAction,
  storePendingAction,
} from './pending-action'

const user = '00000000-0000-4000-8000-000000000001'
const other = '00000000-0000-4000-8000-000000000002'
const action = {
  songId: '00000000-0000-4000-8000-000000000010',
  favorited: true,
  returnTo: '/search?q=hello#results',
}
const key = 'stelody-login-action'
beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())

it('명시적인 로그인 복귀에서만 준비하고 같은 계정·경로에서 한 번 소비한다', () => {
  expect(storePendingAction(action)).toBe(true)
  expect(consumePendingAction(user, action.returnTo)).toBeNull()
  preparePendingAction(user, '/songs')
  expect(consumePendingAction(user, action.returnTo)).toBeNull()
  preparePendingAction(user, action.returnTo)
  expect(consumePendingAction(other, action.returnTo)).toBeNull()
  expect(consumePendingAction(user, '/songs')).toBeNull()
  expect(consumePendingAction(user, action.returnTo)).toMatchObject(action)
  expect(consumePendingAction(user, action.returnTo)).toBeNull()
})

it('세션이 만료된 계정의 작업은 다른 계정 로그인에서 폐기한다', () => {
  storePendingAction({ ...action, favorited: false, expectedUserId: user })
  preparePendingAction(other, action.returnTo)
  expect(readPendingAction()).toBeNull()
})

it.each([
  '{invalid',
  JSON.stringify({ ...action, kind: 'other', createdAt: Date.now() }),
  JSON.stringify({
    ...action,
    kind: 'favorite',
    createdAt: Date.now() - 300_001,
  }),
  JSON.stringify({
    ...action,
    kind: 'favorite',
    createdAt: Date.now() + 60_000,
  }),
  JSON.stringify({
    ...action,
    kind: 'favorite',
    createdAt: Date.now(),
    returnTo: '//evil.example',
  }),
])('손상·만료·미래 시각·외부 복귀 경로를 실행하지 않는다: %s', (raw) => {
  sessionStorage.setItem(key, raw)
  expect(readPendingAction()).toBeNull()
  expect(sessionStorage.getItem(key)).toBeNull()
})

it('브라우저 저장소가 차단되면 자동 재개를 포기하고 오류를 전파하지 않는다', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(storePendingAction(action)).toBe(false)
  expect(() => preparePendingAction(user, action.returnTo)).not.toThrow()
})

it('소비 전에 저장소 삭제가 실패하면 요청을 시작할 작업을 반환하지 않는다', () => {
  storePendingAction(action)
  preparePendingAction(user, action.returnTo)
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(consumePendingAction(user, action.returnTo)).toBeNull()
})
