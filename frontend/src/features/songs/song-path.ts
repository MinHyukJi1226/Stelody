export const isYoutubeId = (value: string) => /^[A-Za-z0-9_-]{11}$/.test(value)

export function songPath(song: { id: string; youtubeId?: string }) {
  const address =
    song.youtubeId && isYoutubeId(song.youtubeId) ? song.youtubeId : song.id
  return `/songs/${encodeURIComponent(address)}`
}
