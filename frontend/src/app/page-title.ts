import { matchPath } from 'react-router'

const titles: [string, string | undefined][] = [
  ['/', 'Stelody · 스텔라이브 노래를 한 곳에서'],
  ['/login', '로그인'],
  ['/privacy', '개인정보처리방침'],
  ['/terms', '이용약관'],
  ['/account/withdrawal', '회원 탈퇴'],
  ['/account', '계정 관리'],
  ['/search', '검색 결과'],
  ['/songs', '전체곡'],
  ['/songs/:songId', undefined],
  ['/members', '전체 멤버'],
  ['/members/:memberId', undefined],
  ['/library', '즐겨찾기'],
  ['/library/playlists', '내 플레이리스트'],
  ['/library/playlists/new', '새 플레이리스트'],
  ['/library/playlists/:playlistId/edit', '플레이리스트 이름 변경'],
  ['/library/playlists/:playlistId/youtube-export', 'YouTube 내보내기'],
  ['/library/playlists/:playlistId', undefined],
  ['/library/youtube', 'YouTube 연결 관리'],
  ['/admin', '관리자 작업함'],
  ['/admin/collection', '수집 관리'],
  ['/admin/review-items', '검토 후보'],
  ['/admin/review-items/:reviewId', '후보 검토'],
  ['/admin/collection-runs/:kind/:runId', '수집 작업 결과'],
  ['/admin/collection-rules', '분류 규칙 관리'],
  ['/admin/channels', '채널 관리'],
  ['/admin/channels/new', '채널 등록'],
  ['/admin/channels/:channelId/edit', '채널 수정'],
  ['/admin/members', '멤버 관리'],
  ['/admin/members/new', '멤버 등록'],
  ['/admin/members/:memberId/edit', '멤버 수정'],
  ['/admin/works', '원곡 관리'],
  ['/admin/works/new', '원곡 등록'],
  ['/admin/works/:workId/edit', '원곡 수정'],
  ['/admin/songs', '노래 관리'],
  ['/admin/songs/new', '노래 등록'],
  ['/admin/songs/:songId/videos/new', '영상 추가'],
  ['/admin/songs/:songId/edit', '노래 수정'],
  ['/admin/reviews', '검토 완료'],
  ['/admin/registrations', '등록 내역'],
  ['/admin/special-event-reviews', '특별 목적 곡 검토'],
  ['/admin/failures', '실패 내역'],
]

export function pageTitle(pathname: string, notFound: boolean) {
  if (notFound) return '페이지를 찾을 수 없습니다'
  const route = titles.find(([path]) => matchPath(path, pathname))
  return route ? route[1] : '페이지를 찾을 수 없습니다'
}
