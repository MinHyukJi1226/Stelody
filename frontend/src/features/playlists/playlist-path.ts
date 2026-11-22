export const isPlaylistPublicId = (value: string) =>
  /^[A-Za-z0-9]{10}$/.test(value)

export function playlistPath(playlist: { id: string; publicId?: string }) {
  return `/library/playlists/${encodeURIComponent(playlist.publicId ?? playlist.id)}`
}
