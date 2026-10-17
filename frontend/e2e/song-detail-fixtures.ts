import type {
  SongDetail,
  SongViews,
} from '../src/features/songs/detail-queries'
import { makeSong } from './fixtures'

export const song = makeSong(1, {
  title: '테스트 커버',
  viewCount: 124800,
  thumbnailUrl: null,
  work: {
    id: '00000000-0000-4000-8000-000000000200',
    title: '원곡 제목',
    artists: [
      { id: '00000000-0000-4000-8000-000000000201', name: '원곡 가수' },
    ],
  },
})
export const detail: SongDetail = {
  song,
  representativeVideo: {
    id: '00000000-0000-4000-8000-000000000300',
    youtubeId: 'aB12cd-E_34',
    url: 'https://www.youtube.com/watch?v=aB12cd-E_34',
    kind: 'OFFICIAL_COVER',
    embeddable: true,
  },
  karaoke: [
    { provider: 'TJ', status: 'REGISTERED', number: '00123' },
    { provider: 'KY', status: 'NOT_LISTED', number: null },
  ],
  links: [{ platform: 'Spotify', url: 'https://open.spotify.com/track/test' }],
  searchHelp: {
    visibility: 'DIFFICULT',
    recommendedQuery: '테스트 멤버 & 노래',
    checkedAt: null,
  },
  relatedSongs: [
    makeSong(2, {
      title: '관련 커버',
      work: song.work,
      participants: [
        {
          ...song.participants[0]!,
          id: '00000000-0000-4000-8000-000000000098',
          name: '다른 멤버',
        },
      ],
    }),
    makeSong(3, {
      title: '협업 커버',
      work: song.work,
      collaboration: true,
      participants: [
        ...song.participants,
        {
          ...song.participants[0]!,
          id: '00000000-0000-4000-8000-000000000097',
          name: '협업 멤버',
        },
      ],
    }),
  ],
  workResources: {
    workId: song.work!.id,
    links: [
      {
        platform: 'YouTube',
        url: 'https://www.youtube.com/watch?v=origin12345',
      },
    ],
    karaoke: [{ provider: 'TJ', status: 'REGISTERED', number: '00999' }],
  },
}
export function makeViews(days = 7): SongViews {
  const points = Array.from({ length: days }, (_, i) => ({
    date: `2026-10-${String(i + 1).padStart(2, '0')}`,
    viewCount: i === 1 ? null : i * 100,
    observedAt:
      i === 1 ? null : `2026-10-${String(i + 1).padStart(2, '0')}T12:00:00Z`,
    source: i === 1 ? null : 'YOUTUBE',
  }))
  return {
    songId: song.id,
    videoId: detail.representativeVideo.id,
    youtubeId: detail.representativeVideo.youtubeId,
    timeZone: 'Asia/Seoul',
    days,
    startDate: points[0]!.date,
    endDate: points.at(-1)!.date,
    collectionStartedAt: '2026-10-01T12:00:00Z',
    latestViewCount: 124800,
    latestObservedAt: '2026-10-07T12:00:00Z',
    publicationId: null,
    publishedAt: null,
    points,
  }
}
