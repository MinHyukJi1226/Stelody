import type { AdminSong, SongInput } from './songs'

export type Draft = {
  title: string
  type: 'ORIGINAL' | 'COVER'
  workId: string | null
  visibility: 'DRAFT' | 'PUBLISHED' | 'HIDDEN'
  representativeVideoId: string | null
  aliases: string[]
  memberIds: string[]
  externalArtistIds: string[]
  isSpecialEvent: boolean
  specialEventLabel: string
  searchVisibility: 'UNCHECKED' | 'NORMAL' | 'DIFFICULT'
  recommendedSearchQuery: string
  links: { platform: string; url: string; sourceUrl: string }[]
  karaoke: {
    provider: 'TJ' | 'KY'
    status: 'UNKNOWN' | 'NOT_LISTED' | 'REGISTERED'
    number: string
    sourceUrl: string
  }[]
}
export const fields = [
  ['title', '곡명'],
  ['type', '곡 종류'],
  ['aliases', '검색 별칭'],
  ['memberIds', '참여 멤버'],
  ['externalArtistIds', '외부 아티스트'],
  ['workId', '연결 원곡'],
  ['representativeVideoId', '대표 영상'],
  ['visibility', '공개 설정'],
  ['isSpecialEvent', '특별 목적 여부'],
  ['specialEventLabel', '특별 목적 문구'],
  ['searchVisibility', '검색 확인'],
  ['recommendedSearchQuery', '권장 검색어'],
  ['links', '곡 링크'],
  ['karaoke', 'TJ/KY'],
] as const satisfies readonly (readonly [keyof Draft, string])[]
export type Field = (typeof fields)[number][0]
export function toDraft(s: AdminSong): Draft {
  return {
    title: s.title,
    type: s.type,
    workId: s.workId,
    visibility: s.visibility,
    representativeVideoId: s.representativeVideoId,
    aliases: [...s.aliases],
    memberIds: [...s.memberIds],
    externalArtistIds: [...s.externalArtistIds],
    isSpecialEvent: s.isSpecialEvent,
    specialEventLabel: s.specialEventLabel ?? '',
    searchVisibility: s.searchVisibility,
    recommendedSearchQuery: s.recommendedSearchQuery ?? '',
    links: s.links.map(({ platform, url, sourceUrl }) => ({
      platform,
      url,
      sourceUrl,
    })),
    karaoke: (['TJ', 'KY'] as const).map((provider) => {
      const k = s.karaoke.find((x) => x.provider === provider)
      return {
        provider,
        status: k?.status ?? 'UNKNOWN',
        number: k?.number ?? '',
        sourceUrl: k?.sourceUrl ?? '',
      }
    }),
  }
}
export const equal = (a: unknown, b: unknown) =>
  JSON.stringify(a) === JSON.stringify(b)
export function toInput(d: Draft, version: number, reason: string): SongInput {
  const {
    workId,
    representativeVideoId,
    recommendedSearchQuery,
    karaoke,
    ...rest
  } = d
  return {
    ...rest,
    version,
    reason: reason.trim(),
    title: d.title.trim(),
    aliases: d.aliases.map((x) => x.trim()),
    ...(workId ? { workId } : {}),
    ...(representativeVideoId ? { representativeVideoId } : {}),
    specialEventLabel: d.isSpecialEvent ? d.specialEventLabel.trim() : null,
    ...(d.searchVisibility !== 'UNCHECKED' && recommendedSearchQuery.trim()
      ? { recommendedSearchQuery: recommendedSearchQuery.trim() }
      : {}),
    links: d.links.map((x) => ({
      platform: x.platform.trim(),
      url: x.url.trim(),
      sourceUrl: x.sourceUrl.trim(),
    })),
    karaoke: karaoke.map((x) => ({
      provider: x.provider,
      status: x.status,
      ...(x.status === 'REGISTERED' && x.number.trim()
        ? { number: x.number.trim() }
        : {}),
      ...(x.sourceUrl.trim() ? { sourceUrl: x.sourceUrl.trim() } : {}),
    })),
  }
}
export function validateDraft(d: Draft, reason: string) {
  const errors: Record<string, string> = {}
  if (!d.title.trim()) errors.title = '곡명을 입력해주세요.'
  if (!reason.trim()) errors.reason = '변경 사유를 입력해주세요.'
  if (d.isSpecialEvent && !d.specialEventLabel.trim())
    errors.specialEventLabel = '특별 목적 표시 문구를 입력해주세요.'
  if (d.searchVisibility === 'DIFFICULT' && !d.recommendedSearchQuery.trim())
    errors.recommendedSearchQuery = '권장 검색어를 입력해주세요.'
  d.links.forEach((x, i) => {
    for (const key of ['platform', 'url', 'sourceUrl'] as const)
      if (!x[key].trim()) errors[`links[${i}].${key}`] = '필수 항목입니다.'
  })
  d.karaoke.forEach((x, i) => {
    if (x.status === 'REGISTERED' && !x.number.trim())
      errors[`karaoke[${i}].number`] = '번호를 입력해주세요.'
    if (x.status !== 'UNKNOWN' && !x.sourceUrl.trim())
      errors[`karaoke[${i}].sourceUrl`] = '확인 출처를 입력해주세요.'
  })
  return errors
}
// Scalar fields and ordered arrays are indivisible choices. No guessed merge of relationships or links.
export function reconcile(base: Draft, draft: Draft, latest: Draft) {
  const choices: Partial<Record<Field, 'draft' | 'latest'>> = {}
  for (const [key] of fields) {
    if (equal(draft[key], latest[key]) || equal(base[key], latest[key]))
      choices[key] = 'draft'
    else if (equal(base[key], draft[key])) choices[key] = 'latest'
  }
  return choices
}
export function chosenDraft(
  draft: Draft,
  latest: Draft,
  choices: Partial<Record<Field, 'draft' | 'latest'>>,
): Draft {
  return Object.fromEntries(
    fields.map(([key]) => [
      key,
      choices[key] === 'latest' ? latest[key] : draft[key],
    ]),
  ) as Draft
}

/** Values the final review will actually submit, including server normalization. */
export function normalizedDraft(d: Draft): Draft {
  return {
    ...d,
    title: d.title.trim(),
    aliases: d.aliases.map((x) => x.trim()),
    specialEventLabel: d.isSpecialEvent ? d.specialEventLabel.trim() : '',
    recommendedSearchQuery:
      d.searchVisibility === 'UNCHECKED' ? '' : d.recommendedSearchQuery.trim(),
    links: d.links.map((x) => ({
      platform: x.platform.trim(),
      url: x.url.trim(),
      sourceUrl: x.sourceUrl.trim(),
    })),
    karaoke: d.karaoke.map((x) => ({
      ...x,
      number: x.status === 'REGISTERED' ? x.number.trim() : '',
      sourceUrl: x.sourceUrl.trim(),
    })),
  }
}
