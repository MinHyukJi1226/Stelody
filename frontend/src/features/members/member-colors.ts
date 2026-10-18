const memberColors: Readonly<Record<string, string>> = {
  '아야츠노 유니': 'var(--color-member-yuni)',
  '사키하네 후야': 'var(--color-member-huya)',
  '시라유키 히나': 'var(--color-member-hina)',
  '네네코 마시로': 'var(--color-member-mashiro)',
  '아카네 리제': 'var(--color-member-lize)',
  '아라하시 타비': 'var(--color-member-tabi)',
  '텐코 시부키': 'var(--color-member-shibuki)',
  '아오쿠모 린': 'var(--color-member-rin)',
  '하나코 나나': 'var(--color-member-nana)',
  '유즈하 리코': 'var(--color-member-riko)',
  '시즈키 토아': 'var(--color-member-toa)',
  '코가네 랑': 'var(--color-member-rang)',
  '하나요 히요리': 'var(--color-member-hiyori)',
  '토키노 메리': 'var(--color-member-meri)',
  '아이리 칸나': 'var(--color-member-kanna)',
}

export const memberAccent = (name: string) =>
  memberColors[name] ?? 'var(--line)'
