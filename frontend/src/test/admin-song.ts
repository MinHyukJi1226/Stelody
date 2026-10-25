import type { AdminSong } from '@/features/admin/songs'
const songId = (index: number) =>
  `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
export const sid = songId(801),
  mid = songId(802),
  wid = songId(803),
  aid = songId(804),
  vid = songId(805)
export function makeAdminSong(): AdminSong {
  return {
    id: sid,
    version: 5,
    title: '노래 제목',
    type: 'COVER',
    workId: wid,
    visibility: 'HIDDEN',
    representativeVideoId: vid,
    aliases: ['검색 별칭'],
    memberIds: [mid],
    externalArtistIds: [],
    isSpecialEvent: false,
    specialEventLabel: null,
    searchVisibility: 'NORMAL',
    recommendedSearchQuery: '원곡 검색어',
    searchCheckedAt: '2026-10-24T03:00:00Z',
    links: [
      {
        id: songId(806),
        platform: 'YouTube Music',
        url: 'https://music.youtube.com/watch?v=test',
        sourceUrl: 'https://www.youtube.com/watch?v=test',
        checkedAt: '2026-10-24T03:00:00Z',
      },
    ],
    karaoke: [
      {
        id: songId(807),
        provider: 'TJ',
        status: 'REGISTERED',
        number: '12345',
        sourceUrl: 'https://tjmedia.com/',
        checkedAt: '2026-10-24T03:00:00Z',
      },
    ],
    videos: [
      {
        id: vid,
        version: 2,
        songId: sid,
        youtubeId: 'testVideoID1',
        kind: 'OFFICIAL_COVER',
        availability: 'PUBLIC',
        sourceTitle: '공식 커버 영상',
        sourcePublishedAt: '2026-09-30T03:00:00Z',
        sourceThumbnailUrl: null,
        sourceObservedAt: '2026-10-24T03:00:00Z',
        publishedAt: '2026-09-30T03:00:00Z',
        thumbnailUrl: null,
        embeddable: true,
      },
    ],
    missingFields: ['KY'],
  }
}
