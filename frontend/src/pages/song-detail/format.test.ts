import { expect, it } from 'vitest'
import { externalUrl, formatDate, youtubeIdValid } from './format'

it('공개일을 한국 시간의 날짜로 표시한다', () => {
  expect(formatDate('2026-10-15T15:00:00Z')).toBe('2026.10.16')
})
it.each([
  'javascript:alert(1)',
  'data:text/html,test',
  '//example.com',
  '/relative',
  'invalid',
])('외부 링크의 잘못된 프로토콜·주소를 노출하지 않는다: %s', (value) => {
  expect(externalUrl(value)).toBeUndefined()
})
it('정상 HTTP(S) 링크와 YouTube 영상 ID를 허용한다', () => {
  expect(externalUrl('https://example.com/a?q=1')).toBe(
    'https://example.com/a?q=1',
  )
  expect(youtubeIdValid('aB12cd-E_34')).toBe(true)
  expect(youtubeIdValid('../bad?x=1')).toBe(false)
})
