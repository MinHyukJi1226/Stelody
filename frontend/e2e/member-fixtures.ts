import type { MemberCard } from '../src/shared/api/types'
import type { MemberDetail } from '../src/features/members/queries'
import { emptyPage, makeSong } from './fixtures'

export const member: MemberCard = {
  id: '00000000-0000-4000-8000-000000000101',
  name: '아라하시 타비',
  generation: 2,
  unitName: 'Universe',
  activityStatus: 'ACTIVE',
  profileImageUrl: null,
  chzzkUrl: 'https://chzzk.naver.com/test',
  xUrl: 'https://x.com/test',
  songCounts: {
    total: 12,
    originals: 3,
    covers: 9,
    collaborations: 4,
    multipleParticipants: 4,
  },
}
export const graduate: MemberCard = {
  ...member,
  id: '00000000-0000-4000-8000-000000000102',
  name: '아이리 칸나',
  activityStatus: 'GRADUATED',
  generation: 1,
  unitName: 'Mystic',
}
export const memberDetail: MemberDetail = {
  member,
  channels: [
    {
      name: member.name,
      youtubeId: 'channel-1',
      url: 'https://www.youtube.com/channel/channel-1',
    },
  ],
}
export const memberSong = (i: number) =>
  makeSong(i, {
    participants: [
      {
        id: member.id,
        name: member.name,
        kind: 'MEMBER',
        activityStatus: member.activityStatus,
      },
    ],
  })
export const memberSongs = {
  ...emptyPage,
  items: [memberSong(1), memberSong(2)],
  totalCount: 2,
}
