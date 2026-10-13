import type { components, paths } from './generated/schema'

export type ApiPaths = paths
export type ApiSchemas = components['schemas']
export type CurrentUser = ApiSchemas['com.stelody.user.dto.CurrentUser']
export type SongPage = ApiSchemas['com.stelody.song.dto.SongDtos.Page']
export type MemberCard = ApiSchemas['com.stelody.member.dto.MemberDtos.Card']
export type PlaylistSummary =
  ApiSchemas['com.stelody.playlist.dto.PlaylistDtos.Summary']
