import { ApiError } from '@/shared/api/errors'

const reserved = new Set([
  'youtuberesult',
  'youtubeerror',
  'code',
  'state',
  'error',
  'error_description',
  'access_token',
  'refresh_token',
  'id_token',
])
export type YouTubeResult = 'success' | 'cancelled' | 'failed' | null

const callbackErrors = new Set([
  'SESSION_EXPIRED',
  'INVALID_YOUTUBE_STATE',
  'INVALID_YOUTUBE_CLIENT',
  'YOUTUBE_ACCOUNT_MISMATCH',
  'YOUTUBE_SCOPE_REQUIRED',
  'YOUTUBE_OFFLINE_ACCESS_REQUIRED',
  'INVALID_YOUTUBE_IDENTITY',
  'YOUTUBE_AUTHORIZATION_CHANGED',
  'YOUTUBE_EXPORT_DISABLED',
  'YOUTUBE_OAUTH_UNAVAILABLE',
  'YOUTUBE_AUTHORIZATION_FAILED',
])

export function readYouTubeFailureCode(params: URLSearchParams) {
  if (readYouTubeResult(params) !== 'failed') return undefined
  const codes = params.getAll('youtubeError')
  return codes.length === 1 && callbackErrors.has(codes[0]!)
    ? codes[0]
    : undefined
}

export function readYouTubeResult(params: URLSearchParams): YouTubeResult {
  if (!params.has('youtubeResult') && !params.has('youtubeError')) return null
  const result = params.get('youtubeResult')
  if (
    params.getAll('youtubeResult').length !== 1 ||
    params.getAll('youtubeError').length > 1 ||
    (result === 'success' && params.has('youtubeError'))
  )
    return 'failed'
  return result === 'success' || result === 'cancelled' ? result : 'failed'
}

export function cleanYouTubeReturn(params: URLSearchParams) {
  const clean = new URLSearchParams(params)
  for (const key of new Set(clean.keys()))
    if (reserved.has(key.toLowerCase())) clean.delete(key)
  return clean
}

export function youtubeErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'YOUTUBE_EXPORT_DISABLED':
        return '지금은 YouTube 연결을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.'
      case 'YOUTUBE_OAUTH_UNAVAILABLE':
      case 'INVALID_YOUTUBE_CLIENT':
        return 'YouTube 인증을 시작할 수 없어요. 잠시 후 다시 시도해 주세요.'
      case 'YOUTUBE_REVOCATION_PENDING':
        return '연결 해제가 처리 중입니다. 완료된 뒤 다시 연결해 주세요.'
    }
  }
  return '요청 결과를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
