import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/errors'
import {
  reviewRequest,
  reviewFieldErrors,
  parseReviewResult,
  type ReviewPreview,
} from './review-completion'

const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const preview: ReviewPreview = {
  items: Array.from({ length: 21 }, (_, i) => ({
    id: id(i + 1),
    version: 5,
    youtubeId: `video${i}`,
    channelId: id(99),
    channelName: '멤버',
    title: `영상 ${i + 1}`,
    publishedAt: null,
    thumbnailUrl: null,
    durationSeconds: null,
    observedAt: null,
    availability: 'PUBLIC',
    disposition: 'REVIEW',
    suggestedType: 'COVER',
    ruleVersion: '1',
    decisionReason: '검토 필요',
    status: 'PENDING',
    reviewNote: null,
    firstSeenAt: '2026-10-26T00:00:00Z',
    sourceExpired: false,
    registeredVideoId: null,
  })),
  hasMore: true,
  channelMembers: { [id(99)]: id(98) },
}
describe('신규 검토 일괄 등록 계약', () => {
  it('모든 대상 ID·버전과 선택한 후보 ID만 고정 순서로 보낸다', () => {
    const request = reviewRequest(preview, [id(21), id(1)], id(100))
    expect(request.items).toHaveLength(21)
    expect(request.items[20]).toEqual({ id: id(21), version: 5 })
    expect(request.selected).toEqual([{ id: id(1) }, { id: id(21) }])
    expect(Object.keys(request)).toEqual(['requestId', 'items', 'selected'])
  })
  it('빈 선택·중복·100건 초과·대상 밖 ID를 전송하지 않는다', () => {
    for (const selected of [
      [],
      [id(1), id(1)],
      [id(999)],
      Array.from({ length: 101 }, (_, i) => id(i + 1)),
    ])
      expect(() => reviewRequest(preview, selected, id(100))).toThrow()
  })
  it('후보별 분류 오류를 전송 순서에 맞는 후보로 연결한다', () => {
    const request = reviewRequest(preview, [id(21), id(1)], id(100))
    const error = new ApiError(409, {
      code: 'CANDIDATE_CLASSIFICATION_REQUIRED',
      fieldErrors: [
        {
          field: 'selected[1].id',
          code: 'CONTENT_TYPE_CONFLICT',
          message: '유형 불일치',
        },
        {
          field: 'selected[0].id',
          code: 'CONTENT_REQUIRES_REVIEW',
          message: '내용 확인',
        },
        {
          field: 'selected[0].id',
          code: 'TITLE_REQUIRES_EDIT',
          message: '제목 확인',
        },
        { field: 'selected[999].id', code: 'INVALID', message: '범위 밖 오류' },
      ],
    })
    expect(reviewFieldErrors(error, request)).toEqual({
      candidates: {
        [id(21)]: ['기존 곡 유형 제안과 현재 분류가 일치하지 않습니다.'],
        [id(1)]: [
          '영상 내용을 추가로 검토해야 합니다.',
          '영상 제목으로 등록할 곡명을 구성할 수 없습니다.',
        ],
      },
      general: ['범위 밖 오류'],
    })
  })
  it('다른 후보·중복 결과·과다 삭제·잘못된 재전송 결과를 성공으로 표시하지 않는다', () => {
    const request = reviewRequest(preview, [id(1)], id(100))
    const result = {
      registeredSongs: [{ reviewId: id(1), songId: id(101) }],
      deletedCount: 20,
      replayed: false,
    }
    expect(parseReviewResult(result, request)).toEqual(result)
    for (const invalid of [
      { ...result, registeredSongs: [{ reviewId: id(2), songId: id(101) }] },
      {
        ...result,
        registeredSongs: [...result.registeredSongs, ...result.registeredSongs],
      },
      { ...result, deletedCount: 21 },
      { ...result, replayed: true },
    ])
      expect(() => parseReviewResult(invalid, request)).toThrow()
    expect(
      parseReviewResult({ ...result, deletedCount: 0, replayed: true }, request)
        .replayed,
    ).toBe(true)
  })
})
