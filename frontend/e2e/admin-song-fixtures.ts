import type { Page } from '@playwright/test'
import type { SongInput } from '../src/features/admin/songs'
import { mockAdmin } from './admin-fixtures'
import { makeSong } from './fixtures'
export { sid } from '../src/test/admin-song'
import { sid, mid, wid, aid, makeAdminSong } from '../src/test/admin-song'
export const editPath = `/admin/songs/${sid}/edit?returnTo=${encodeURIComponent('/admin/songs?q=노래&page=1&size=10')}`
export async function mockSongEditor(page: Page) {
  const admin = await mockAdmin(page)
  const state = {
    admin,
    meCalls: 0,
    song: makeAdminSong(),
    puts: [] as SongInput[],
    gets: [] as URL[],
    mode: 'success' as
      'success' | 'validation' | 'conflict' | 'lost' | 'malformed',
    detailError: 0,
    listError: false,
    selectorError: false,
    saveGate: Promise.resolve(),
    detailGate: Promise.resolve(),
  }
  await page.route('**/api/v1/me', (route) => {
    state.meCalls++
    return route.fallback()
  })
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } }),
  )
  await page.route('**/api/v1/admin/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname.replace('/api/v1/admin/', '')
    if (path === `songs/${sid}` && route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as SongInput
      state.puts.push(body)
      await state.saveGate
      if (state.mode === 'validation')
        return route.fulfill({
          status: 400,
          json: {
            code: 'INVALID_CATALOG_REQUEST',
            title: '입력 항목을 확인해주세요',
            fieldErrors: [
              {
                field: 'title',
                code: 'INVALID_VALUE',
                message: '곡명을 확인해주세요',
              },
              {
                field: 'links[0].url',
                code: 'INVALID_FORMAT',
                message: 'HTTPS 링크를 입력해주세요',
              },
            ],
          },
        })
      if (state.mode === 'conflict')
        return route.fulfill({
          status: 409,
          json: { code: 'CATALOG_VERSION_CONFLICT' },
        })
      state.song = {
        ...state.song,
        ...body,
        version: body.version + 1,
        workId: body.workId ?? null,
        representativeVideoId: body.representativeVideoId ?? null,
        recommendedSearchQuery: body.recommendedSearchQuery ?? null,
        specialEventLabel: body.specialEventLabel ?? null,
        links: body.links.map((x, i) => ({
          ...x,
          id: makeSong(806 + i).id,
          checkedAt: '2026-10-25T03:00:00Z',
        })),
        karaoke: body.karaoke.map((x, i) => ({
          ...x,
          id: makeSong(807 + i).id,
          number: x.number ?? null,
          sourceUrl: x.sourceUrl ?? null,
          checkedAt: '2026-10-25T03:00:00Z',
        })),
      }
      if (state.mode === 'lost') return route.abort('failed')
      return route.fulfill({
        json:
          state.mode === 'malformed'
            ? { item: { id: sid } }
            : { item: state.song, possibleDuplicateIds: [] },
      })
    }
    state.gets.push(url)
    if (path === `songs/${sid}`) {
      await state.detailGate
      return route.fulfill(
        state.detailError
          ? {
              status: state.detailError,
              json: {
                code:
                  state.detailError === 404
                    ? 'CATALOG_RESOURCE_NOT_FOUND'
                    : 'ACCESS_DENIED',
              },
            }
          : { json: state.song },
      )
    }
    if (path === 'songs')
      return route.fulfill(
        state.listError
          ? { status: 503, json: { code: 'UNAVAILABLE' } }
          : {
              json: {
                items: [
                  {
                    id: sid,
                    name: state.song.title,
                    version: state.song.version,
                    status: state.song.visibility,
                    participants: [
                      {
                        id: mid,
                        name: '참여 멤버',
                        kind: 'MEMBER',
                        confirmed: false,
                      },
                    ],
                    discoveredAt: null,
                    missingFields: ['aliases'],
                    informationComplete: true,
                  },
                ],
                page: Number(url.searchParams.get('page')),
                size: Number(url.searchParams.get('size')),
                hasNext: Number(url.searchParams.get('page')) < 1,
              },
            },
      )
    if (/^(members|artists|works)$/.test(path))
      return route.fulfill(
        state.selectorError
          ? { status: 503, json: { code: 'UNAVAILABLE' } }
          : {
              json: {
                items: [
                  {
                    id:
                      path === 'members' ? mid : path === 'artists' ? aid : wid,
                    name:
                      path === 'members'
                        ? '참여 멤버'
                        : path === 'artists'
                          ? '원곡 아티스트'
                          : '원곡 제목',
                    version: 1,
                    status: path === 'members' ? 'ACTIVE' : null,
                  },
                ],
                page: Number(url.searchParams.get('page')),
                size: 20,
                hasNext: Number(url.searchParams.get('page')) === 0,
              },
            },
      )
    if (path.startsWith('members/'))
      return route.fulfill({ json: { id: mid, name: '참여 멤버' } })
    if (path.startsWith('artists/'))
      return route.fulfill({ json: { id: aid, name: '원곡 아티스트' } })
    if (path.startsWith('works/'))
      return route.fulfill({
        json: { id: wid, title: '원곡 제목', artistIds: [aid] },
      })
    return route.fallback()
  })
  return state
}
