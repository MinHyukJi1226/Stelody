import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  readPendingReview,
  storePendingReview,
  clearPendingReview,
  type PendingReview,
} from './review-receipt'
import { reviewRequest, type ReviewPreview } from './review-completion'
const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const preview: ReviewPreview = {
  items: [
    {
      id: id(1),
      version: 7,
      youtubeId: 'video',
      title: '원본 영상',
      channelId: null,
      channelName: null,
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
      firstSeenAt: '2026-10-26T00:00:00Z',
      sourceExpired: false,
      registeredVideoId: null,
      registeredSongId: null,
    },
  ],
  hasMore: false,
  channelMembers: {},
}
function pending(): PendingReview {
  return {
    user: id(9),
    request: reviewRequest(preview, [id(1)], id(10)),
    titles: ['원본 영상'],
    preview,
  }
}
beforeEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})
describe('검토 요청의 탭별 보관·복구', () => {
  it('이전 입력형 요청을 새 ID 전용 요청으로 변환하거나 삭제하지 않는다', () => {
    const original = pending()
    const legacy = {
      ...original,
      request: {
        ...original.request,
        selected: [{ id: id(1), song: { title: '이전 곡' }, kind: 'OTHER' }],
      },
    }
    const key = `stelody-review-completion:${original.user}`
    const raw = JSON.stringify(legacy)
    sessionStorage.setItem(key, raw)
    expect(readPendingReview(original.user)).toEqual({ state: 'invalid' })
    expect(sessionStorage.getItem(key)).toBe(raw)
  })
  it('요청 ID·본문의 키·배열 순서와 고정 대상을 그대로 복구한다', () => {
    const original = pending()
    storePendingReview(original)
    const recovered = readPendingReview(original.user)
    expect(recovered.state).toBe('pending')
    if (recovered.state !== 'pending') throw Error('missing receipt')
    expect(JSON.stringify(recovered.pending.request)).toBe(
      JSON.stringify(original.request),
    )
    expect(recovered.pending.preview).toEqual(original.preview)
  })
  it('다른 계정의 요청을 표시하거나 지우지 않는다', () => {
    storePendingReview(pending())
    expect(readPendingReview(id(8))).toEqual({ state: 'none' })
    clearPendingReview(id(8))
    expect(readPendingReview(id(9)).state).toBe('pending')
  })
  it('저장 실패나 쓰기 후 검증 실패를 전송 가능한 상태로 처리하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('quota')
    })
    expect(() => storePendingReview(pending())).toThrow('전송하지 않았습니다')
    vi.restoreAllMocks()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => undefined)
    expect(() => storePendingReview(pending())).toThrow('전송하지 않았습니다')
  })
  it('손상된 요청이나 다른 스냅샷으로 바뀐 요청을 새 검토로 오해하지 않는다', () => {
    const original = pending(),
      key = `stelody-review-completion:${original.user}`
    sessionStorage.setItem(key, '{broken')
    expect(readPendingReview(original.user)).toEqual({ state: 'invalid' })
    const changed = structuredClone(original)
    changed.preview.items[0]!.version++
    sessionStorage.setItem(key, JSON.stringify(changed))
    expect(readPendingReview(original.user)).toEqual({ state: 'invalid' })
  })
})
