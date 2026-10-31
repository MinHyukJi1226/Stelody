import { candidateSchema } from './song-videos'
import { it, expect } from 'vitest'
import {
  validateReview,
  reviewReturnPath,
  reviewDetailPath,
} from './review-items'
it('검토 대기·제외만 변경 가능하며 등록 완료 변경을 거절한다', () => {
  expect(validateReview('PENDING', '확인')).toEqual({})
  expect(validateReview('IGNORED', '확인')).toEqual({})
  expect(validateReview('REGISTERED', '확인')).toHaveProperty('status')
})
it('매 변경에서 새로운 사유 1–500자를 요구한다', () => {
  expect(validateReview('IGNORED', ' ')).toHaveProperty('reason')
  expect(validateReview('IGNORED', 'a'.repeat(501))).toHaveProperty('reason')
})
it('목록 필터와 페이지 문맥만 복원한다', () => {
  const p = '/admin/review-items?status=IGNORED&page=2&size=10'
  expect(reviewReturnPath(p)).toBe(p)
  expect(
    new URL(reviewDetailPath('id', p), 'https://example.com').searchParams.get(
      'returnTo',
    ),
  ).toBe(p)
  expect(reviewReturnPath('//evil.example/admin/review-items')).toBe(
    '/admin/review-items',
  )
})

it('구버전 후보 상세에서 곡 ID 누락을 허용하며 새 곡 ID는 그대로 보존한다', () => {
  const item = {
    id: '00000000-0000-4000-8000-000000000001',
    version: 1,
    youtubeId: 'abcdefghijk',
    channelId: null,
    channelName: null,
    title: '후보',
    publishedAt: null,
    thumbnailUrl: null,
    durationSeconds: null,
    observedAt: null,
    availability: 'PUBLIC',
    disposition: 'REVIEW',
    suggestedType: 'COVER',
    ruleVersion: '1',
    decisionReason: '검토',
    status: 'PENDING',
    reviewNote: null,
    firstSeenAt: '2026-10-31T00:00:00Z',
    sourceExpired: false,
    registeredVideoId: null,
  }
  expect(candidateSchema.parse(item).registeredSongId).toBeNull()
  expect(
    candidateSchema.parse({ ...item, registeredSongId: item.id })
      .registeredSongId,
  ).toBe(item.id)
  expect(
    candidateSchema.safeParse({ ...item, registeredSongId: 'bad' }).success,
  ).toBe(false)
})
